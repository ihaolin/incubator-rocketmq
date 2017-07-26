/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.netty.NettySystemConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NameServer启动类
 * <p>
 *     org.apache.rocketmq.namesrv.NamesrvStartup -c /path/to/ns.properties
 * </p>
 */
public class NamesrvStartup {

    public static Properties properties = null;

    public static CommandLine commandLine = null;

    public static void main(String[] args) {
        main0(args);
    }

    public static NamesrvController main0(String[] args) {

        // 设置RPC调用中的MQ版本号
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

        if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_SNDBUF_SIZE)) {
            // socket 发送buffer大小
            NettySystemConfig.socketSndbufSize = 4096;
        }

        if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_RCVBUF_SIZE)) {
            // socket 接收buffer大小
            NettySystemConfig.socketRcvbufSize = 4096;
        }

        try {

            Options options = ServerUtil.buildCommandlineOptions(new Options());
            commandLine =
                ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options),
                    new PosixParser());
            if (null == commandLine) {
                System.exit(-1);
                return null;
            }

            // NameServer相关配置
            final NamesrvConfig namesrvConfig = new NamesrvConfig();

            // NettyServer相关配置
            final NettyServerConfig nettyServerConfig = new NettyServerConfig();

            // 服务监听端口
            nettyServerConfig.setListenPort(9876);

            if (commandLine.hasOption('c')) {

                // 是否有配置文件
                String file = commandLine.getOptionValue('c');
                if (file != null) {
                    InputStream in = new BufferedInputStream(new FileInputStream(file));
                    properties = new Properties();
                    properties.load(in);

                    // 将配置文件中的配置
                    MixAll.properties2Object(properties, namesrvConfig);
                    MixAll.properties2Object(properties, nettyServerConfig);

                    namesrvConfig.setConfigStorePath(file);

                    System.out.printf("load config properties file OK, " + file + "%n");
                    in.close();
                }
            }

            if (commandLine.hasOption('p')) {

                // 打印可配置项
                MixAll.printObjectProperties(null, namesrvConfig);
                MixAll.printObjectProperties(null, nettyServerConfig);
                System.exit(0);
            }

            // 将命令行参数设置到namesrvConfig中
            MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);

            if (null == namesrvConfig.getRocketmqHome()) {
                // RocketMQ Home not set
                System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
                    + " variable in your environment to match the location of the RocketMQ installation%n");
                System.exit(-2);
            }

            // 日志配置
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(lc);
            lc.reset();
            // $ROCKETMQ_HOME/conf/logback_namesrv.xml为日志配置文件
            configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");
            final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

            // 打印配置
            MixAll.printObjectProperties(log, namesrvConfig);
            MixAll.printObjectProperties(log, nettyServerConfig);

            // 构建NamesrvController
            final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

            // remember all configs to prevent discard
            controller.getConfiguration().registerConfig(properties);

            // 初始化NamesrvController，初始化内部各种组件
            boolean initResult = controller.initialize();
            if (!initResult) {
                // 初始化失败，关闭
                controller.shutdown();
                System.exit(-3);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                private volatile boolean hasShutdown = false;
                private AtomicInteger shutdownTimes = new AtomicInteger(0);

                @Override
                public void run() {
                    synchronized (this) {
                        log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet());
                        if (!this.hasShutdown) {
                            this.hasShutdown = true;
                            long begineTime = System.currentTimeMillis();
                            controller.shutdown();
                            long consumingTimeTotal = System.currentTimeMillis() - begineTime;
                            log.info("shutdown hook over, consuming time total(ms): " + consumingTimeTotal);
                        }
                    }
                }
            }, "ShutdownHook"));

            // 启动NamesrvController
            controller.start();

            String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
            log.info(tip);
            System.out.printf(tip + "%n");

            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config item");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }
}
