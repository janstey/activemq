/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.bugs;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertTrue;

public class AMQ4368Test {

    private static final Logger LOG = LoggerFactory.getLogger(AMQ4368Test.class);

    private BrokerService broker;
    private ActiveMQConnectionFactory connectionFactory;
    private final Destination destination = new ActiveMQQueue("large_message_queue");
    private String connectionUri;

    @Before
    public void setUp() throws Exception {
        broker = createBroker();
        connectionUri = broker.addConnector("tcp://localhost:0").getPublishableConnectString();
        broker.start();
        connectionFactory = new ActiveMQConnectionFactory(connectionUri);
    }

    @After
    public void tearDown() throws Exception {
        broker.stop();
        broker.waitUntilStopped();
    }

    protected BrokerService createBroker() throws Exception {
        BrokerService broker = new BrokerService();

        PolicyEntry policy = new PolicyEntry();
        policy.setUseCache(false);
        broker.setDestinationPolicy(new PolicyMap());
        broker.getDestinationPolicy().setDefaultEntry(policy);

        KahaDBPersistenceAdapter kahadb = new KahaDBPersistenceAdapter();
        kahadb.setChecksumJournalFiles(true);
        kahadb.setCheckForCorruptJournalFiles(true);
        kahadb.setCleanupInterval(1000);

        kahadb.deleteAllMessages();
        broker.setPersistenceAdapter(kahadb);
        broker.getSystemUsage().getMemoryUsage().setLimit(1024*1024*100);
        return broker;
    }

    abstract class Client implements Runnable   {
        private final String name;
        final AtomicBoolean done = new AtomicBoolean();
        CountDownLatch doneLatch = new CountDownLatch(1);
        Connection connection;
        Session session;
        final AtomicLong size = new AtomicLong();

        Client(String name) {
            this.name = name;
        }

        public void start() {
            LOG.info("Starting: " + name);
            new Thread(this, name).start();
        }

        public void stopAsync() {
            done.set(true);
        }

        public void stop() throws InterruptedException {
            stopAsync();
            if (!doneLatch.await(20, TimeUnit.MILLISECONDS)) {
                try {
                    connection.close();
                    doneLatch.await();
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void run() {
            try {
                connection = createConnection();
                connection.start();
                try {
                    session = createSession();
                    work();
                } finally {
                    try {
                        connection.close();
                    } catch (JMSException ignore) {
                    }
                    LOG.info("Stopped: " + name);
                }
            } catch (Exception e) {
                e.printStackTrace();
                done.set(true);
            } finally {
                doneLatch.countDown();
            }
        }

        protected Session createSession() throws JMSException {
            return connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        }

        protected Connection createConnection() throws JMSException {
            return connectionFactory.createConnection();
        }

        abstract protected void work() throws Exception;
    }

    class ProducingClient extends Client {

        ProducingClient(String name) {
            super(name);
        }

        private String createMessage() {
            StringBuffer stringBuffer = new StringBuffer();
            for (long i = 0; i < 1000000; i++) {
                stringBuffer.append("1234567890");
            }
            return stringBuffer.toString();
        }

        @Override
        protected void work() throws Exception {
            String data = createMessage();
            MessageProducer producer = session.createProducer(destination);
            while (!done.get()) {
                producer.send(session.createTextMessage(data));
                long i = size.incrementAndGet();
                if ((i % 1000) == 0) {
                    LOG.info("produced " + i + ".");
                }
            }
        }
    }

    class ConsumingClient extends Client {

        public ConsumingClient(String name) {
            super(name);
        }

        @Override
        protected void work() throws Exception {
            MessageConsumer consumer = session.createConsumer(destination);
            while (!done.get()) {
                Message msg = consumer.receive(100);
                if (msg != null) {
                    size.incrementAndGet();
                }
            }
        }
    }

    @Test
    public void testENTMQ220() throws InterruptedException, JMSException {
        LOG.info("Start test.");

        ProducingClient producer1 = new ProducingClient("1");
        ProducingClient producer2 = new ProducingClient("2");
        ConsumingClient listener1 = new ConsumingClient("subscriber-1");
        try {

            producer1.start();
            producer2.start();
            listener1.start();

            long lastSize = listener1.size.get();
            for (int i = 0; i < 10; i++) {
                Thread.sleep(2000);
                long size = listener1.size.get();
                LOG.info("Listener 1: consumed: " + (size - lastSize));
                assertTrue("No messages received on iteration: " + i, size > lastSize);
                lastSize = size;
            }
        } finally {
            LOG.info("Stopping clients");
            producer1.stop();
            producer2.stop();
            listener1.stop();
        }
    }
}