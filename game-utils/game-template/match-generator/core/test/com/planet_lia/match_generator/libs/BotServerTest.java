package com.planet_lia.match_generator.libs;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.planet_lia.match_generator.libs.BotListener.MessageSender.BOT;
import static com.planet_lia.match_generator.libs.BotListener.MessageSender.MATCH_GENERATOR;
import static com.planet_lia.match_generator.libs.BotServer.BOT_LISTENER_TOKEN_HEADER_KEY;
import static org.junit.jupiter.api.Assertions.*;

class BotServerTest {
    @Test
    void badBotsAndTokens() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "t1", "b2"};
        GeneralConfig config = new GeneralConfig();
        config.allowedNumbersOfBots = new int[]{2};
        assertThrows(Error.class, () ->
                new BotServer(config, new Timer(), 9000, botsAndTokens));
    }

    @Test
    void isNumberOfBotsAllowed() throws Exception {
        GeneralConfig config = new GeneralConfig();
        Timer timer = new Timer();

        config.allowedNumbersOfBots = new int[]{2, 4, 5};
        assertThrows(Error.class, () ->
                new BotServer(config, timer, 9000, new String[]{"b1", "t1"}));
        config.allowedNumbersOfBots = new int[]{2};
        assertThrows(Error.class, () ->
                new BotServer(config, timer, 9000, new String[]{"b1", "t1", "b2"}));
        config.allowedNumbersOfBots = new int[]{1};
        assertThrows(Error.class, () ->
                new BotServer(config, timer, 9000, new String[]{"b1", "t1", "b2"}));
        config.allowedNumbersOfBots = new int[]{2};
        assertDoesNotThrow(() ->
                new BotServer(config, timer, 9000, new String[]{"b1", "t1", "b2", "t2"}));
        config.allowedNumbersOfBots = new int[]{2, 4};
        assertDoesNotThrow(() ->
                new BotServer(config, timer, 9000, new String[]{"b1", "t1", "b2", "t2", "b3", "t3", "b4", "t4"}));
    }

    @Test
    void botWrongToken() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "t1", "b2", "WRONG_TOKEN"};
        int port = 9000;
        GeneralConfig config = new GeneralConfig();
        config.connectingBotsTimeout = 2;
        config.allowedNumbersOfBots = new int[]{2};

        BotServer server = new BotServer(config, new Timer(), port, botsAndTokens);
        server.start();

        connectBot(port, "t1");
        connectBot(port, "t2");

        assertThrows(Error.class, server::waitForBotsToConnect);
        server.stop();
    }

    @Test
    void botsSameTokens() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "_", "b2", "_"};
        int port = 9000;
        GeneralConfig config = new GeneralConfig();
        config.connectingBotsTimeout = 2;
        config.allowedNumbersOfBots = new int[]{2};

        BotServer server = new BotServer(config, new Timer(), port, botsAndTokens);
        server.start();

        connectBot(port, "a");
        connectBot(port, "_");

        assertThrows(Error.class, server::waitForBotsToConnect);

        connectBot(port, "_");
        assertDoesNotThrow(server::waitForBotsToConnect);

        server.stop();
    }

    @Test
    void basicFlow() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "t1", "b2", "t2"};
        int port = 9000;
        GeneralConfig config = new GeneralConfig();
        Timer timer = new Timer();
        config.allowedNumbersOfBots = new int[]{2};
        config.connectingBotsTimeout = 1;
        config.botFirstResponseTimeout = 0.2f;
        config.botResponseTimeout = 0.2f;
        config.maxTimeoutsPerBot = 2;
        config.botResponseTotalDurationMax = 100;

        BotServer server = new BotServer(config, timer, port, botsAndTokens);
        server.start();

        ClientMock bot1 = connectBot(port, "t1");
        ClientMock bot2 = connectBot(port, "t2");

        // Test if bots connect
        assertDoesNotThrow(server::waitForBotsToConnect);

        server.sendToAll("request");
        bot1.send("response1");
        bot2.send("response2");

        // Test if the response was received
        server.waitForBotsToRespond();
        assertEquals("response1", server.getLastResponseData(0));
        assertEquals("response2", server.getLastResponseData(1));
        assertEquals(0, server.getNumberOfTimeouts(0));
        assertEquals(0, server.getNumberOfTimeouts(1));
        assertFalse(server.isDisqualified(0));
        assertFalse(server.isDisqualified(1));

        server.stop();
    }

    @Test
    void botDisqualificationNumTimeoutsExceeded() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "t1", "b2", "t2"};
        int port = 9000;
        GeneralConfig config = new GeneralConfig();
        Timer timer = new Timer();
        config.allowedNumbersOfBots = new int[]{2};
        config.connectingBotsTimeout = 1;
        config.botFirstResponseTimeout = 0.2f;
        config.botResponseTimeout = 0.2f;
        config.maxTimeoutsPerBot = 2;
        config.botResponseTotalDurationMax = 2;

        BotServer server = new BotServer(config, timer, port, botsAndTokens);
        server.start();

        ClientMock bot1 = connectBot(port, "t1");
        connectBot(port, "t2");

        // Test if bots connect
        server.waitForBotsToConnect();

        // Should timeout
        server.sendToAll("request");
        server.waitForBotsToRespond();

        assertNull(server.getLastResponseData(0));
        assertNull(server.getLastResponseData(1));
        assertEquals(1, server.getNumberOfTimeouts(0));
        assertEquals(1, server.getNumberOfTimeouts(1));
        assertFalse(server.isDisqualified(0));
        assertFalse(server.isDisqualified(1));

        // Bot 2 should be disqualified
        timer.time += 1;
        server.sendToAll("request");
        bot1.send("response1");
        // This send should be ignored
        bot1.send("response3");
        server.waitForBotsToRespond();

        assertEquals("response1", server.getLastResponseData(0));
        assertNull(server.getLastResponseData(1));
        assertEquals(1, server.getNumberOfTimeouts(0));
        assertEquals(2, server.getNumberOfTimeouts(1));
        assertFalse(server.isDisqualified(0));
        assertTrue(server.isDisqualified(1));
        assertEquals(-1f, server.getDisqualificationTime(0));
        assertEquals(1f, server.getDisqualificationTime(1));
        assertTrue(server.getDisqualificationReason(1).length() > 0);
        assertFalse(server.allBotsDisqualified());

        // Both bots disqualified
        timer.time += 1;
        server.sendToAll("request");
        server.waitForBotsToRespond();

        assertNull(server.getLastResponseData(0));
        assertEquals(2, server.getNumberOfTimeouts(0));
        assertEquals(2, server.getNumberOfTimeouts(1));
        assertTrue(server.isDisqualified(0));
        assertTrue(server.isDisqualified(1));
        assertEquals(2f, server.getDisqualificationTime(0));
        assertEquals(1f, server.getDisqualificationTime(1));
        assertTrue(server.allBotsDisqualified());

        server.stop();
    }


    @Test
    void botListenerDisabledTest() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "t1", "b2", "t2"};
        int port = 9000;
        GeneralConfig config = new GeneralConfig();
        config.allowedNumbersOfBots = new int[]{2};
        Timer timer = new Timer();
        BotServer server = new BotServer(config, timer, port, botsAndTokens);

        assertFalse(server.isBotListenerEnabled());
    }

    @Test
    void botListenerTest() throws Exception {
        String[] botsAndTokens = new String[]{"b1", "t1", "b2", "t2"};
        int port = 9000;
        GeneralConfig config = new GeneralConfig();
        Timer timer = new Timer();
        config.allowedNumbersOfBots = new int[]{2};
        config.connectingBotsTimeout = 1;
        config.botFirstResponseTimeout = 2f;
        config.botResponseTimeout = 2f;
        config.maxTimeoutsPerBot = 2;
        config.botResponseTotalDurationMax = 100;
        String botListenerToken = "btt";

        BotServer server = new BotServer(config, timer, port, botsAndTokens, botListenerToken);
        server.start();

        ClientMock bot1 = connectBot(port, "t1");
        ClientMock bot2 = connectBot(port, "t2");

        // Test if bots connect
        assertDoesNotThrow(server::waitForBotsToConnect);
        assertTrue(server.isBotListenerEnabled());
        assertFalse(server.isBotListenerConnected());

        ClientMock botListener = connectClient(port, BOT_LISTENER_TOKEN_HEADER_KEY, botListenerToken);
        Thread.sleep(100);
        assertTrue(server.isBotListenerConnected());

        // First exchange
        server.sendToAll("request1");
        bot1.send("response1");
        bot2.send("response2");
        server.waitForBotsToRespond();

        // Second exchange
        server.sendToAll("request2");
        bot1.send("response3");
        bot2.send("response4");
        server.waitForBotsToRespond();

        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(MATCH_GENERATOR, 0, "request1")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(MATCH_GENERATOR, 1, "request1")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(BOT, 0, "response1")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(BOT, 1, "response2")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(MATCH_GENERATOR, 0, "request2")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(MATCH_GENERATOR, 1, "request2")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(BOT, 0, "response3")));
        assertTrue(botListener.receivedData.contains(
                BotListener.createMessage(BOT, 1, "response4")));

        server.stop();
    }

    ClientMock connectBot(int port, String token) throws URISyntaxException {
        return connectClient(port, "token", token);
    }

    ClientMock connectClient(int port, String headerKey, String headerValue) throws URISyntaxException {
        HashMap<String, String> header = new HashMap<>();
        header.put(headerKey, headerValue);
        ClientMock client = new ClientMock(new URI("ws://localhost:" + port), header);
        client.connect();
        return client;
    }
}


class ClientMock extends WebSocketClient {

    ArrayList<String> receivedData = new ArrayList<>();

    ClientMock(URI serverUri, Map<String, String> httpHeaders) {
        super(serverUri, httpHeaders);
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {}

    @Override
    public void onMessage(String message) {
        this.receivedData.add(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {}

    @Override
    public void onError(Exception ex) {}
}