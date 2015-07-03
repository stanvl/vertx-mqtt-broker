package io.github.giovibal.mqtt;

import io.github.giovibal.mqtt.parser.MQTTDecoder;
import io.github.giovibal.mqtt.parser.MQTTEncoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.dna.mqtt.moquette.proto.messages.*;

import static org.dna.mqtt.moquette.proto.messages.AbstractMessage.*;

/**
 * Created by giovanni on 07/05/2014.
 * Base class for connection handling, 1 tcp connection corresponds to 1 instance of this class.
 */
public abstract class MQTTSocket implements MQTTPacketTokenizer.MqttTokenizerListener, Handler<Buffer> {

    protected Vertx vertx;
    private MQTTDecoder decoder;
    private MQTTEncoder encoder;
    private MQTTPacketTokenizer tokenizer;
    protected MQTTSession session;
    private ConfigParser config;

    public MQTTSocket(Vertx vertx, ConfigParser config) {
        this.decoder = new MQTTDecoder();
        this.encoder = new MQTTEncoder();
        this.tokenizer = new MQTTPacketTokenizer();
        this.tokenizer.registerListener(this);
        this.vertx = vertx;
        this.config = config;
    }

    abstract protected void sendMessageToClient(Buffer bytes);
    abstract protected void closeConnection();

    public void shutdown() {
        if(tokenizer!=null) {
            tokenizer.removeAllListeners();
            tokenizer = null;
        }
        if(session!=null) {
            session.shutdown();
            session = null;
        }
        vertx = null;
    }

    @Override
    public void handle(Buffer buffer) {
        tokenizer.process(buffer.getBytes());
    }

    @Override
    public void onToken(byte[] token, boolean timeout) throws Exception {
        try {
            Buffer buffer = Buffer.buffer(token);
            AbstractMessage message = decoder.dec(buffer);
            onMessageFromClient(message);
        } catch (Throwable ex) {
            String clientInfo = getClientInfo();
            Container.logger().error(clientInfo +", Bad error in processing the message", ex);
        }
    }

    @Override
    public void onError(Throwable e) {
        String clientInfo = getClientInfo();
        Container.logger().error(clientInfo +", "+ e.getMessage(), e);
        if(e instanceof CorruptedFrameException) {
            closeConnection();
        }
    }

    private void onMessageFromClient(AbstractMessage msg) throws Exception {
        switch (msg.getMessageType()) {
            case CONNECT:
                ConnectMessage connect = (ConnectMessage)msg;
                if(session == null) {
                    session = new MQTTSession(vertx, config);
                    session.setPublishMessageHandler(this::sendMessageToClient);
                } else {
                    Container.logger().warn("Session alredy allocated ...");
                }
                session.handleConnectMessage(connect, authenticated -> {
                    if (authenticated) {
                        ConnAckMessage connAck = new ConnAckMessage();
                        sendMessageToClient(connAck);
                    } else {
                        Container.logger().error("Authentication failed! clientID= " + connect.getClientID() + " username=" + connect.getUsername());
                        closeConnection();
                    }
                });
                break;
            case SUBSCRIBE:
                SubscribeMessage subscribeMessage = (SubscribeMessage)msg;
                session.handleSubscribeMessage(subscribeMessage);
                SubAckMessage subAck = new SubAckMessage();
                subAck.setMessageID(subscribeMessage.getMessageID());
                for(SubscribeMessage.Couple c : subscribeMessage.subscriptions()) {
                    QOSType qos = new QOSUtils().toQos(c.getQos());
                    subAck.addType(qos);
                }
                if(subscribeMessage.isRetainFlag()) {
                    /*
                    When a new subscription is established on a topic,
                    the last retained message on that topic should be sent to the subscriber with the Retain flag set.
                    If there is no retained message, nothing is sent
                    */
                }
                sendMessageToClient(subAck);
                break;
            case UNSUBSCRIBE:
                UnsubscribeMessage unsubscribeMessage = (UnsubscribeMessage)msg;
                session.handleUnsubscribeMessage(unsubscribeMessage);
                UnsubAckMessage unsubAck = new UnsubAckMessage();
                unsubAck.setMessageID(unsubscribeMessage.getMessageID());
                sendMessageToClient(unsubAck);
                break;
            case PUBLISH:
                PublishMessage publish = (PublishMessage)msg;
                session.handlePublishMessage(publish);
                switch (publish.getQos()) {
                    case RESERVED:
                        break;
                    case MOST_ONE:
                        break;
                    case LEAST_ONE:
                        PubAckMessage pubAck = new PubAckMessage();
                        pubAck.setMessageID(publish.getMessageID());
                        sendMessageToClient(pubAck);
                        break;
                    case EXACTLY_ONCE:
                        PubRecMessage pubRec = new PubRecMessage();
                        pubRec.setMessageID(publish.getMessageID());
                        sendMessageToClient(pubRec);
                        break;
                }
                break;
            case PUBREC:
                PubRecMessage pubRec = (PubRecMessage)msg;
                PubRelMessage prelResp = new PubRelMessage();
                prelResp.setMessageID(pubRec.getMessageID());
                prelResp.setQos(QOSType.LEAST_ONE);
                sendMessageToClient(prelResp);
                break;
            case PUBCOMP:
                break;
            case PUBREL:
                PubRelMessage pubRel = (PubRelMessage)msg;
                PubCompMessage pubComp = new PubCompMessage();
                pubComp.setMessageID(pubRel.getMessageID());
                sendMessageToClient(pubComp);
                break;
            case PUBACK:
                // A PUBACK message is the response to a PUBLISH message with QoS level 1.
                // A PUBACK message is sent by a server in response to a PUBLISH message from a publishing client,
                // and by a subscriber in response to a PUBLISH message from the server.
                break;
            case PINGREQ:
                PingRespMessage pingResp = new PingRespMessage();
                sendMessageToClient(pingResp);
                break;
            case DISCONNECT:
                DisconnectMessage disconnectMessage = (DisconnectMessage)msg;
                handleDisconnect(disconnectMessage);
                break;
            default:
                Container.logger().warn("type of message not known: "+ msg.getClass().getSimpleName());
                break;
        }

        // TODO: forward mqtt message to backup server

    }


    public void sendMessageToClient(AbstractMessage message) {
        try {
            Buffer b1 = encoder.enc(message);
            sendMessageToClient(b1);
        } catch(Throwable e) {
            Container.logger().error(e.getMessage(), e);
        }
    }

    private void handleDisconnect(DisconnectMessage disconnectMessage) {
        session.handleDisconnect(disconnectMessage);
        session = null;
    }


    protected String getClientInfo() {
        String clientInfo = "Session n/a";
        if(session != null) {
            clientInfo = session.getClientInfo();
        }
        return clientInfo;
    }


}
