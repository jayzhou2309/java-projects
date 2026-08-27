package project.demotradingapp.execeptions;

public class BadRequestExeception extends RuntimeException{
    public BadRequestExeception(String message){
        super(message);
    }
}
