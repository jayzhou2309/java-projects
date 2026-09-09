package project.stockrecommendationengine.broker;

public class BrokerException extends RuntimeException {
    public enum Code { LOGIN_REQUIRED, SESSION_NOT_READY, ACCOUNT_NOT_ALLOWED, RATE_LIMITED,
        UNAVAILABLE, INVALID_RESPONSE, POSITION_LIMIT, INVALID_ARGUMENT, INTERRUPTED }
    private final Code code;
    public BrokerException(Code code) {
        super(code.name());
        this.code = code;
    }
    public Code code() { return code; }
}
