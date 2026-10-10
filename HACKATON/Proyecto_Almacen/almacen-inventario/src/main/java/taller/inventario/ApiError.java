package taller.inventario;

/** Error de negocio que la API convierte en una respuesta HTTP. */
public final class ApiError extends RuntimeException {
    public final int status;
    public ApiError(int status, String message) { super(message); this.status = status; }
}
