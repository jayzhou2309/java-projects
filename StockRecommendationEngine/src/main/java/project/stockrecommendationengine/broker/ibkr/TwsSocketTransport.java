package project.stockrecommendationengine.broker.ibkr;

import com.ib.client.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import project.stockrecommendationengine.broker.BrokerException;
import static project.stockrecommendationengine.broker.BrokerException.Code.*;

final class TwsSocketTransport implements TwsTransport {
    private volatile EClientSocket client;
    private Socket socket;
    private Thread dispatcher;
    private Thread readerThread;

    @Override public void connect(EWrapper callback, IbkrProperties properties) {
        close();
        var signal = new EJavaSignal();
        var next = new EClientSocket(callback, signal);
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()), properties.getTimeoutMs());
            // Bound the SDK's initial protocol handshake as well as TCP connect.
            socket.setSoTimeout(properties.getTimeoutMs());
            client = next;
            next.eConnect(socket, properties.getClientId());
            if (!next.isConnected()) throw new BrokerException(SESSION_NOT_READY);
            socket.setSoTimeout(0);
            var reader = new EReader(next, signal);
            readerThread = reader;
            reader.setDaemon(true);
            reader.start();
            dispatcher = new Thread(() -> {
                try {
                    while (next.isConnected() && !Thread.currentThread().isInterrupted()) {
                        signal.waitForSignal();
                        if (next.isConnected()) reader.processMsgs();
                    }
                } catch (Exception ex) {
                    callback.error(new IllegalStateException("TWS_READER_FAILED"));
                }
            }, "tws-callbacks");
            dispatcher.setDaemon(true);
            dispatcher.start();
        } catch (Exception ex) {
            close();
            throw new BrokerException(SESSION_NOT_READY);
        }
    }
    @Override public boolean connected() { return client != null && client.isConnected(); }
    @Override public void requestTime() { client.reqCurrentTime(); }
    @Override public void search(int id, String symbol) { client.reqMatchingSymbols(id, symbol); }
    @Override public void contract(int id, long conid) {
        var contract = new Contract();
        contract.conid(Math.toIntExact(conid));
        client.reqContractDetails(id, contract);
    }
    @Override public void positions(int id, String account) { client.reqPositionsMulti(id, account, ""); }
    @Override public void cancelPositions(int id) { if (connected()) client.cancelPositionsMulti(id); }
    @Override public void quote(int id, Contract contract, int dataType) {
        client.reqMarketDataType(dataType);
        client.reqMktData(id, contract, "", false, false, List.of());
    }
    @Override public void cancelQuote(int id) { if (connected()) client.cancelMktData(id); }
    @Override public void history(int id, Contract contract, int days) {
        // Regular trading hours, daily TRADES bars, yyyyMMdd bar dates, no streaming updates.
        client.reqHistoricalData(id, contract, "", days + " D", "1 day", "TRADES", 1, 1, false, List.of());
    }
    @Override public void cancelHistory(int id) { if (connected()) client.cancelHistoricalData(id); }
    @Override public void close() {
        if (client != null) client.eDisconnect();
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        if (dispatcher != null) dispatcher.interrupt();
        join(dispatcher);
        join(readerThread);
        client = null;
    }
    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(500); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
