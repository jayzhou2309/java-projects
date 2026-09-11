package project.stockrecommendationengine.broker.ibkr;

import com.ib.client.Contract;
import com.ib.client.EWrapper;

/** Socket operations used by this application. Deliberately exposes no order methods. */
interface TwsTransport extends AutoCloseable {
    void connect(EWrapper callback, IbkrProperties properties);
    boolean connected();
    void requestTime();
    void search(int id, String symbol);
    void contract(int id, long conid);
    void positions(int id, String account);
    void cancelPositions(int id);
    void quote(int id, Contract contract, int dataType);
    void cancelQuote(int id);
    @Override void close();
}
