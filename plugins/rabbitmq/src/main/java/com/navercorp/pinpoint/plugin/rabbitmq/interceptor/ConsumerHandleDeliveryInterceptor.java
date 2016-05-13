/*
 * Copyright 2016 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.plugin.rabbitmq.interceptor;

import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.plugin.rabbitmq.RabbitMQConstants;
import com.navercorp.pinpoint.bootstrap.context.MethodDescriptor;
import com.navercorp.pinpoint.bootstrap.context.SpanId;
import com.navercorp.pinpoint.bootstrap.context.SpanRecorder;
import com.navercorp.pinpoint.bootstrap.context.Trace;
import com.navercorp.pinpoint.bootstrap.context.TraceContext;
import com.navercorp.pinpoint.bootstrap.context.TraceId;
import com.navercorp.pinpoint.bootstrap.interceptor.SpanSimpleAroundInterceptor;
import com.navercorp.pinpoint.bootstrap.util.NumberUtils;
import com.navercorp.pinpoint.plugin.rabbitmq.RabbitMQUtils;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.AMQConnection;

import java.util.Map;

/**
 * @author Jinkai.Ma
 */
public class ConsumerHandleDeliveryInterceptor extends SpanSimpleAroundInterceptor {

    public ConsumerHandleDeliveryInterceptor(TraceContext traceContext, MethodDescriptor descriptor) {
        super(traceContext, descriptor, ConsumerHandleDeliveryInterceptor.class);
    }

    @Override
    protected Trace createTrace(Object target, Object[] args) {
        AMQP.BasicProperties properties = (AMQP.BasicProperties) args[2];
        Map<String, Object> headers = properties.getHeaders();
        // If this transaction is not traceable, mark as disabled.
        if (headers.get(RabbitMQConstants.META_DO_NOT_TRACE) != null) {
            return traceContext.disableSampling();
        }

        Object transactionId = headers.get(RabbitMQConstants.META_TRANSACTION_ID);
        // If there's no trasanction id, a new trasaction begins here.
        if (transactionId == null) {
            return traceContext.newTraceObject();
        }

        // otherwise, continue tracing with given data.
        long parentSpanID = NumberUtils.parseLong(headers.get(RabbitMQConstants.META_PARENT_SPAN_ID).toString(), SpanId.NULL);
        long spanID = NumberUtils.parseLong(headers.get(RabbitMQConstants.META_SPAN_ID).toString(), SpanId.NULL);
        short flags = NumberUtils.parseShort(headers.get(RabbitMQConstants.META_FLAGS).toString(), (short) 0);
        TraceId traceId = traceContext.createTraceId(transactionId.toString(), parentSpanID, spanID, flags);

        return traceContext.continueTraceObject(traceId);
    }


    @Override
    protected void doInBeforeTrace(SpanRecorder recorder, Object target, Object[] args) {
        // must validate
        DefaultConsumer consumer = (DefaultConsumer) target;
        AMQConnection connection = (AMQConnection) consumer.getChannel().getConnection();

        // You have to record a service type within Server range.
        recorder.recordServiceType(RabbitMQConstants.RABBITMQ_SERVICE_TYPE);

        Envelope envelope = (Envelope) args[1];
        final String destination = RabbitMQUtils.createDestination(envelope.getExchange(), envelope.getRoutingKey());
        recorder.recordRpcName(connection.toString() + destination);

        AMQP.BasicProperties properties = (AMQP.BasicProperties) args[2];

        // Record client address, server address.
        recorder.recordEndPoint(RabbitMQUtils.getLocalAddress(connection));
        recorder.recordRemoteAddress(RabbitMQUtils.getRemoteAddress(connection));

        if (!recorder.isRoot()) {
            Map<String, Object> headers = properties.getHeaders();
            Object parentApplicationNameObject = headers.get(RabbitMQConstants.META_PARENT_APPLICATION_NAME);
            if (parentApplicationNameObject != null) {
                String parentApplicationName = parentApplicationNameObject.toString();
                short parentApplicationType = NumberUtils.parseShort(headers.get(RabbitMQConstants.META_PARENT_APPLICATION_TYPE).toString(), ServiceType.UNDEFINED.getCode());
                recorder.recordParentApplication(parentApplicationName, parentApplicationType);
                Object host = headers.get(RabbitMQConstants.META_HOST);
                if (host != null) {
                    recorder.recordAcceptorHost(host.toString());
                } else {
                    recorder.recordAcceptorHost(destination);
                }
            }
        }
    }

    @Override
    protected void doInAfterTrace(SpanRecorder recorder, Object target, Object[] args, Object result, Throwable throwable) {
        // must validate
        recorder.recordApi(methodDescriptor);
        if (throwable != null) {
            recorder.recordException(throwable);
        }
    }
}
