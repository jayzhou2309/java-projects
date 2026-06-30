package project.demoshippingappv1.model;

public enum ShipmentStatus {
    CREATED, IN_TRANSIT, DELIVERED, FAILED;

    public static boolean isValid(String value){
        for(ShipmentStatus s: values()){
            if (s.name().equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
