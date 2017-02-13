package net.becvert.cordova;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import com.pusher.java_websocket.WebSocket;
import com.pusher.java_websocket.WebSocketAdapter;
import com.pusher.java_websocket.WebSocketImpl;
import com.pusher.java_websocket.drafts.Draft;
import com.pusher.java_websocket.exceptions.InvalidDataException;
import com.pusher.java_websocket.framing.CloseFrame;
import com.pusher.java_websocket.handshake.ClientHandshake;
import com.pusher.java_websocket.handshake.ServerHandshakeBuilder;
import com.pusher.java_websocket.server.WebSocketServer;

import android.util.Log;

public class WebSocketServerImpl extends WebSocketServer {

    public boolean failed = false;

    private CallbackContext callbackContext;

    private List<String> origins;

    private List<String> protocols;

    private Map<String, WebSocket> UUIDSockets = new HashMap<String, WebSocket>();

    private Map<WebSocket, String> socketsUUID = new HashMap<WebSocket, String>();

    public WebSocketServerImpl(int port) {
        super(new InetSocketAddress(port));
    }

    public CallbackContext getCallbackContext() {
        return this.callbackContext;
    }

    public void setCallbackContext(CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
    }

    public void setOrigins(List<String> origins) {
        this.origins = origins;
    }

    public void setProtocols(List<String> protocols) {
        this.protocols = protocols;
    }

    public void setTcpNoDelay(Boolean on) {
        if (on) {
            this.setWebSocketFactory(new WebSocketServerFactory() {
                @Override
                public ByteChannel wrapChannel(SocketChannel channel, SelectionKey key) throws IOException {
                    return (SocketChannel) channel;
                }

                @Override
                public WebSocketImpl createWebSocket(WebSocketAdapter a, List<Draft> d, Socket s) {
                    try {
                        Log.d(WebSocketServerPlugin.TAG, "setting TCP_NODELAY");
                        s.setTcpNoDelay(true);
                    } catch (SocketException e) {
                        Log.e(WebSocketServerPlugin.TAG, "SocketException: failed to set TCP_NODELAY");
                    }
                    return new WebSocketImpl(a, d);
                }

                @Override
                public WebSocketImpl createWebSocket(WebSocketAdapter a, Draft d, Socket s) {
                    try {
                        Log.d(WebSocketServerPlugin.TAG, "setting TCP_NODELAY");
                        s.setTcpNoDelay(true);
                    } catch (SocketException e) {
                        Log.e(WebSocketServerPlugin.TAG, "SocketException: failed to set TCP_NODELAY");
                    }
                    return new WebSocketImpl(a, d);
                }
            });
        }
    }

    private String getAcceptedProtocol(ClientHandshake clientHandshake) {
        String acceptedProtocol = null;
        String secWebSocketProtocol = clientHandshake.getFieldValue("Sec-WebSocket-Protocol");
        if (secWebSocketProtocol != null && !secWebSocketProtocol.equals("")) {
            String[] requestedProtocols = secWebSocketProtocol.split(", ");
            for (int i = 0, l = requestedProtocols.length; i < l; i++) {
                if (protocols.indexOf(requestedProtocols[i]) > -1) {
                    // returns first matching protocol.
                    // assumes in order of preference.
                    acceptedProtocol = requestedProtocols[i];
                    break;
                }
            }
        }
        return acceptedProtocol;
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft,
            ClientHandshake request) throws InvalidDataException {

        ServerHandshakeBuilder serverHandshakeBuilder = super.onWebsocketHandshakeReceivedAsServer(conn, draft,
                request);

        if (origins != null) {
            String origin = request.getFieldValue("Origin");
            if (origins.indexOf(origin) == -1) {
                Log.w(WebSocketServerPlugin.TAG, "handshake: origin denied: " + origin);
                throw new InvalidDataException(CloseFrame.REFUSE);
            }
        }

        if (protocols != null) {
            String acceptedProtocol = getAcceptedProtocol(request);
            if (acceptedProtocol == null) {
                String secWebSocketProtocol = request.getFieldValue("Sec-WebSocket-Protocol");
                Log.w(WebSocketServerPlugin.TAG, "handshake: protocol denied: " + secWebSocketProtocol);
                throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR);
            } else {
                serverHandshakeBuilder.put("Sec-WebSocket-Protocol", acceptedProtocol);
            }
        }

        return serverHandshakeBuilder;
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
        Log.v(WebSocketServerPlugin.TAG, "onopen");

        String uuid = null;
        while (uuid == null || UUIDSockets.containsKey(uuid)) {
            // prevent collision
            uuid = UUID.randomUUID().toString();
        }
        UUIDSockets.put(uuid, webSocket);
        socketsUUID.put(webSocket, uuid);

        try {
            JSONObject httpFields = new JSONObject();
            Iterator<String> iterator = clientHandshake.iterateHttpFields();
            while (iterator.hasNext()) {
                String httpField = iterator.next();
                httpFields.put(httpField, clientHandshake.getFieldValue(httpField));
            }

            JSONObject conn = new JSONObject();
            conn.put("uuid", uuid);
            conn.put("remoteAddr", webSocket.getRemoteSocketAddress().getAddress().getHostAddress());

            String acceptedProtocol = "";
            if (protocols != null) {
                acceptedProtocol = getAcceptedProtocol(clientHandshake);
            }
            conn.put("acceptedProtocol", acceptedProtocol);

            conn.put("httpFields", httpFields);
            conn.put("resource", clientHandshake.getResourceDescriptor());

            JSONObject status = new JSONObject();
            status.put("action", "onOpen");
            status.put("conn", conn);

            Log.d(WebSocketServerPlugin.TAG, "onopen result: " + status.toString());
            PluginResult result = new PluginResult(PluginResult.Status.OK, status);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);

        } catch (JSONException e) {
            Log.e(WebSocketServerPlugin.TAG, e.getMessage(), e);
            callbackContext.error("Error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String msg) {
        Log.v(WebSocketServerPlugin.TAG, "onmessage");

        String uuid = socketsUUID.get(webSocket);

        if (uuid != null) {
            try {
                JSONObject status = new JSONObject();
                status.put("action", "onMessage");
                status.put("uuid", uuid);
                status.put("msg", msg);

                Log.d(WebSocketServerPlugin.TAG, "onmessage result: " + status.toString());
                PluginResult result = new PluginResult(PluginResult.Status.OK, status);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);

            } catch (JSONException e) {
                Log.e(WebSocketServerPlugin.TAG, e.getMessage(), e);
                callbackContext.error("Error: " + e.getMessage());
            }
        } else {
            Log.d(WebSocketServerPlugin.TAG, "onmessage: unknown websocket");
        }

    }

    @Override
    public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
        Log.v(WebSocketServerPlugin.TAG, "onclose");

        if (webSocket != null) {

            String uuid = socketsUUID.get(webSocket);

            if (uuid != null) {
                try {
                    JSONObject status = new JSONObject();
                    status.put("action", "onClose");
                    status.put("uuid", uuid);
                    status.put("code", code);
                    status.put("reason", reason);
                    status.put("wasClean", remote);

                    Log.d(WebSocketServerPlugin.TAG, "onclose result: " + status.toString());
                    PluginResult result = new PluginResult(PluginResult.Status.OK, status);
                    result.setKeepCallback(true);
                    callbackContext.sendPluginResult(result);

                } catch (JSONException e) {
                    Log.e(WebSocketServerPlugin.TAG, e.getMessage(), e);
                    callbackContext.error("Error: " + e.getMessage());
                } finally {
                    socketsUUID.remove(webSocket);
                    UUIDSockets.remove(uuid);
                }
            } else {
                Log.d(WebSocketServerPlugin.TAG, "onclose: unknown websocket");
            }

        }

    }

    @Override
    public void onError(WebSocket webSocket, Exception exception) {
        Log.v(WebSocketServerPlugin.TAG, "onerror");

        if (exception != null) {
            Log.e(WebSocketServerPlugin.TAG, "onerror: " + exception.getMessage());
            exception.printStackTrace();
        }

        if (webSocket == null) {
            // server error
            try {
                try {
                    // normally already stopped. just making sure!
                    this.stop();
                } catch (IOException e) {
                    // fail silently
                    Log.e(WebSocketServerPlugin.TAG, e.getMessage(), e);
                } catch (InterruptedException e) {
                    // fail silently
                    Log.e(WebSocketServerPlugin.TAG, e.getMessage(), e);
                }

                JSONObject status = new JSONObject();
                status.put("action", "onFailure");
                status.put("addr", this.getAddress().getAddress().getHostAddress());
                status.put("port", this.getPort());
                if (exception != null) {
                    status.put("reason", exception.getMessage());
                }

                Log.d(WebSocketServerPlugin.TAG, "onerror result: " + status.toString());
                PluginResult result = new PluginResult(PluginResult.Status.OK, status);
                result.setKeepCallback(false);
                callbackContext.sendPluginResult(result);

            } catch (JSONException e) {
                Log.e(WebSocketServerPlugin.TAG, e.getMessage(), e);
                callbackContext.error("Error: " + e.getMessage());

            } finally {
                failed = true;
                callbackContext = null;
                UUIDSockets = null;
                socketsUUID = null;
            }

        } else {
            // fatal error
            if (webSocket.isOpen()) {
                webSocket.close(CloseFrame.UNEXPECTED_CONDITION);
            }
        }

    }

    public void send(String uuid, String msg) {
        Log.v(WebSocketServerPlugin.TAG, "send");

        WebSocket webSocket = UUIDSockets.get(uuid);

        if (webSocket != null && !this.failed) {
            webSocket.send(msg);
        } else {
            Log.d(WebSocketServerPlugin.TAG, "send: unknown websocket");
        }

    }

    public void close(String uuid, int code, String reason) {
        Log.v(WebSocketServerPlugin.TAG, "close");

        WebSocket webSocket = UUIDSockets.get(uuid);

        if (webSocket != null && !this.failed) {
            if (code == -1) {
                webSocket.close(CloseFrame.NORMAL);
            } else {
                webSocket.close(code, reason);
            }
            UUIDSockets.remove(uuid);
            socketsUUID.remove(webSocket);
        } else {
            Log.d(WebSocketServerPlugin.TAG, "close: unknown websocket");
        }

    }

}