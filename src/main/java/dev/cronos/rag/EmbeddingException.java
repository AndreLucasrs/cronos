package dev.cronos.rag;

public final class EmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
