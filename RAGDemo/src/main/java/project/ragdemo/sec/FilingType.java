package project.ragdemo.sec;

public enum FilingType {
    TEN_K("10-K"),
    TEN_Q("10-Q"),
    EIGHT_K("8-K"),
    OTHER("OTHER");

    private final String secForm;

    FilingType(String secForm){
        this.secForm = secForm;
    }

    public static FilingType fromSecForm(String form){
        for (FilingType type : values()){
            if (type.secForm.equals(form)) return type;
        }
        return OTHER;
    }

    public String getSecForm() {return secForm;}
}
