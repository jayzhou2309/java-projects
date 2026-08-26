package project.seckillsystem.Entity;

public enum ActivityStatus {
    NOT_STARTED(0), ONGOING(1), ENDED(2), OFFLINE(3);
    private final int value;
    ActivityStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
