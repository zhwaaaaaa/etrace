package io.etrace.consumer.component.processor;

import io.etrace.agent.config.AgentConfiguration;
import io.etrace.common.message.trace.CallStackV1;
import io.etrace.common.message.trace.MessageItem;
import io.etrace.common.message.trace.codec.JSONCodecV1;
import io.etrace.common.pipeline.Component;
import io.etrace.common.pipeline.Processor;
import io.etrace.common.pipeline.impl.DefaultAsyncTask;
import io.etrace.common.util.RequestIdHelper;
import io.etrace.common.util.TimeHelper;
import io.etrace.consumer.config.ConsumerProperties;
import io.etrace.consumer.metrics.MetricsService;
import io.etrace.consumer.model.MessageBlock;
import io.etrace.consumer.util.CallStackUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import kafka.message.MessageAndMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.xerial.snappy.SnappyInputStream;

import java.io.*;
import java.time.Duration;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.newArrayList;
import static io.etrace.consumer.metrics.MetricName.*;

@org.springframework.stereotype.Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TraceDecodeProcessor extends DefaultAsyncTask implements Processor {

    public final Logger LOGGER = LoggerFactory.getLogger(TraceDecodeProcessor.class);

    @Autowired
    public MetricsService metricsService;

    @Autowired
    protected ConsumerProperties consumerProperties;

    public Timer hdfsTimer;
    public Counter parseError;

    public TraceDecodeProcessor(String name, Component component, Map<String, Object> params) {
        super(name, component, params);
    }

    @Override
    public void startup() {
        super.startup();
        hdfsTimer = Metrics.timer(TASK_DURATION, Tags.of("type", "decode-to-hdfs"));
        parseError = Metrics.counter(CALLSTACK_PARSE_ERROR, Tags.empty());
    }

    @Override
    public void processEvent(Object key, Object event) {
        if (!(event instanceof MessageAndMetadata)) {
            return;
        }

        byte[] body = ((MessageAndMetadata<byte[], byte[]>)event).message();

        try {
            List<MessageItem> items = decode(body);
            writeToHDFS(items, body);
        } catch (Exception e) {
            error(body, e);
        }
    }

    public void error(byte[] body, Exception e) {
        if (AgentConfiguration.isDebugMode()) {
            try {
                ByteArrayInputStream bain = new ByteArrayInputStream(body);
                SnappyInputStream in = new SnappyInputStream(bain);
                String result = new BufferedReader(new InputStreamReader(in))
                    .lines().collect(Collectors.joining("\n"));

                LOGGER.error("fail to processEvent: data:\n {}", result, e);
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        } else {
            LOGGER.error("fail to processEvent.", e);
        }

        parseError.increment();
    }

    public void writeToHDFS(List<MessageItem> items, byte[] body) {
        if (items != null && !items.isEmpty()) {
            ListIterator<MessageItem> it = items.listIterator();
            while (it.hasNext()) {
                MessageItem item = it.next();
                CallStackV1 callStack = item.getCallStack();

                // validate message. subclass can override validateMessage()
                if (validateMessage(item)) {
                    CallStackUtil.removeClientAppId(callStack);
                } else {
                    it.remove();
                    if (AgentConfiguration.isDebugMode()) {
                        LOGGER.info("Discard MessageItem: AppId[{}], RequestId[{}], Host[{}], HostName[{}].",
                            callStack.getAppId(), callStack.getRequestId(), callStack.getHostIp(),
                            callStack.getHostName());
                    }
                }
            }
            if (!items.isEmpty()) {
                long start = System.currentTimeMillis();
                component.dispatchAll("", new MessageBlock(items, body));
                hdfsTimer.record(Duration.ofMillis(System.currentTimeMillis() - start));
            }
        }
    }

    public List<MessageItem> decode(byte[] compressData) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressData);

        try (DataInputStream in = new DataInputStream(new SnappyInputStream(bais))) {
            List<MessageItem> messageItems = newArrayList();
            int offset = 0;
            while (in.available() > 0) {
                int dataLen = in.readInt();
                byte[] data = new byte[dataLen];
                in.readFully(data);
                CallStackV1 callStack = JSONCodecV1.decodeToV1FromArrayFormat(data);

                MessageItem item = new MessageItem(callStack);

                //set CallStack offset in message block
                item.setOffset(offset);

                messageItems.add(item);

                offset += 4 + dataLen;
            }
            return messageItems;
        }
    }

    protected boolean validateMessage(MessageItem item) {
        long timestampInRequestId = RequestIdHelper.getTimestamp(item.getRequestId());
        if (item.getCallStack() == null) {
            metricsService.invalidCallStack(CHECK_DATA_INTEGRATION, "nullCallstack");
        } else if (!CallStackUtil.validate(item.getCallStack())) {
            metricsService.invalidCallStack(CHECK_DATA_INTEGRATION, item.getCallStack().getAppId());
        } else if (!TimeHelper.isInPeriod(timestampInRequestId, 24 * consumerProperties.getKeeper(), 24)) {
            metricsService.invalidCallStack(CHECK_TIMESTAMP, item.getCallStack().getAppId());
        } else {
            return true;
        }
        return false;
    }
}
