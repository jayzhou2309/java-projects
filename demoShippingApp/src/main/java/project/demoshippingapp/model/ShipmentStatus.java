package project.demoshippingapp.model;

public enum ShipmentStatus {
    CREATED, IN_TRANSIT, DELIVERED, FAILED;

    public static boolean isValid(String status){
        for (ShipmentStatus s: values()){
            if(s.name().equalsIgnoreCase(status)) return true;
        }
        return false;
    }
}
