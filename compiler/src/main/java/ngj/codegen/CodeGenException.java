package ngj.codegen;

public class CodeGenException extends Exception {
    public CodeGenException(String message) {
        super(message);
    }
    
    public CodeGenException(String message, Throwable cause) {
        super(message, cause);
    }
}
