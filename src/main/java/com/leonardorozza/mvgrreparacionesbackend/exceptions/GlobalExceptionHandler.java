package com.leonardorozza.mvgrreparacionesbackend.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ================================
    // 404 - Recurso no encontrado
    // ================================
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex,
                                                   HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Recurso no encontrado",
                ex.getMessage(),
                request.getRequestURI(),
                ex.getCode(),
                null
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // ================================
    // 413 - Archivo por encima del límite permitido
    // ================================
    @ExceptionHandler(ArchivoDemasiadoGrandeException.class)
    public ResponseEntity<ApiError> handleArchivoDemasiadoGrande(
            ArchivoDemasiadoGrandeException ex,
            HttpServletRequest request) {
        return archivoDemasiadoGrande(ex.getMessage(), request);
    }

    /** También cubre el rechazo temprano del multipart resolver. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        return archivoDemasiadoGrande(
                "El archivo supera el máximo permitido de 1 MiB.", request);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiError> handleMultipartInvalido(
            Exception ex, HttpServletRequest request) {
        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Solicitud inválida",
                "La parte multipart 'file' es obligatoria y debe contener una imagen válida.",
                request.getRequestURI(),
                "QR_COBRO_INVALIDO",
                Map.of("parteRequerida", "file")
        );
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "Tipo de contenido no soportado",
                "El tipo de contenido de la solicitud no está soportado.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    // ================================
    // 400 - Bad Request
    // ================================
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex,
                                                     HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Solicitud inválida",
                ex.getMessage(),
                request.getRequestURI(),
                ex.getCode(),
                optionalDetails(ex.getDetails())
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ================================
    // 401 - Unauthorized
    // ================================
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex,
                                                       HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "No autorizado",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    // ================================
    // VALIDACIÓN @Valid (MethodArgumentNotValidException)
    // ================================
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationErrors(MethodArgumentNotValidException ex,
                                                           HttpServletRequest request) {

        String message = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(" | "));

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Error de validación",
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ================================
    // VALIDACIÓN en Path params (@Validated)
    // ================================
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Error de validación",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ================================
    // 402 - Límite del plan alcanzado / suscripción no vigente (invita a upgrade)
    // ================================
    @ExceptionHandler(PlanLimitException.class)
    public ResponseEntity<ApiError> handlePlanLimit(PlanLimitException ex,
                                                    HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.PAYMENT_REQUIRED.value(),
                "Límite del plan alcanzado",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(error);
    }

    // ================================
    // 401 - Credenciales inválidas / fallo de autenticación
    // ================================
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                         HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "No autorizado",
                "Usuario o contraseña incorrectos",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    // ================================
    // 403 - Sin permisos para el recurso
    // ================================
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.FORBIDDEN.value(),
                "Acceso denegado",
                "No tenés permisos para realizar esta acción",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    // ================================
    // 409 - Conflicto con el estado actual del recurso (ej: transición de estado no permitida)
    // ================================
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex,
                                                   HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflicto de estado",
                ex.getMessage(),
                request.getRequestURI(),
                ex.getCode(),
                optionalDetails(ex.getDetails())
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // ================================
    // 409 - Conflicto de integridad (ej: teléfono/email duplicado)
    // ================================
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {

        var closure=closureFailure(ex,request);
        if(closure!=null) return closure;
        if (privatePhotoDeletionPending(ex)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                    LocalDateTime.now(), HttpStatus.CONFLICT.value(), "Conflicto de datos",
                    "Eliminá primero las fotos privadas de la reparación e intentá nuevamente.",
                    request.getRequestURI(), "FOTOS_PRIVADAS_PENDIENTES", null));
        }
        // Los mensajes del driver pueden incluir valores del registro; no se vuelcan a logs.
        log.warn("Violación de integridad de datos en {}.", request.getRequestURI());

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.CONFLICT.value(),
                "Conflicto de datos",
                "Ya existe un registro con esos datos (verificá teléfono o email).",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    private static boolean privatePhotoDeletionPending(Throwable failure) {
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for (Throwable current=failure; current!=null && seen.add(current); current=current.getCause()) {
            if(current instanceof org.hibernate.exception.ConstraintViolationException sql
                    && "23514".equals(sql.getSQLState())
                    && "foto_privada_borrado_pendiente".equals(sql.getConstraintName())) return true;
            if (privatePhotoDriverConstraint(current)) return true;
        }
        return false;
    }

    /** Driver is runtime-scoped: use only its typed diagnostic field, never its SQL/message text. */
    private static boolean privatePhotoDriverConstraint(Throwable failure) {
        if (!(failure instanceof java.sql.SQLException sql) || !"23514".equals(sql.getSQLState())
                || !"org.postgresql.util.PSQLException".equals(failure.getClass().getName())) return false;
        try {
            Object server=failure.getClass().getMethod("getServerErrorMessage").invoke(failure);
            if (server==null || !"org.postgresql.util.ServerErrorMessage".equals(server.getClass().getName())) return false;
            return "foto_privada_borrado_pendiente".equals(server.getClass().getMethod("getConstraint").invoke(server));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    // ================================
    // 400 - Tipo de parámetro inválido (ej: enum inexistente en query param)
    // ================================
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Parámetro inválido",
                "Valor inválido para el parámetro '" + ex.getName() + "'",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ================================
    // 400 - Body JSON ilegible o enum inválido en el cuerpo
    // ================================
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex,
                                                      HttpServletRequest request) {

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Solicitud inválida",
                "El cuerpo de la solicitud es inválido o tiene un formato incorrecto.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ================================
    // 502 - Error con el proveedor de pagos (MercadoPago)
    // ================================
    @ExceptionHandler(PagoException.class)
    public ResponseEntity<ApiError> handlePago(PagoException ex,
                                               HttpServletRequest request) {

        log.error("Error con el proveedor de pagos: {}", ex.getMessage());

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.BAD_GATEWAY.value(),
                "Error con el proveedor de pagos",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }

    // ================================
    // 500 - Excepciones no controladas
    // ================================
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneralError(Exception ex,
                                                       HttpServletRequest request) {

        var closure=closureFailure(ex,request);
        if(closure!=null) return closure;
        // Log completo del lado del servidor; al cliente NO se le filtra el detalle interno.
        log.error("Error no controlado en {}", request.getRequestURI(), ex);

        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Error interno del servidor",
                "Ocurrió un error inesperado. Intentá nuevamente más tarde.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler({com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException.class,
            com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException.class})
    public ResponseEntity<ApiError> handleClosure(RuntimeException failure,HttpServletRequest request) {
        return closureFailure(failure,request);
    }

    private ResponseEntity<ApiError> closureFailure(Throwable failure,HttpServletRequest request) {
        boolean blocked=failure instanceof com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
        boolean busy=failure instanceof com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException;
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(Throwable current=failure;current!=null && seen.add(current);current=current.getCause()) {
            if(current instanceof java.sql.SQLException sql) {
                blocked|="P0033".equals(sql.getSQLState());busy|="P0034".equals(sql.getSQLState());
            }
        }
        if(!blocked && !busy) return null;
        var status=blocked?HttpStatus.LOCKED:HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).header("Cache-Control","private, no-store")
            .body(new ApiError(LocalDateTime.now(),status.value(),status.getReasonPhrase(),
              blocked?"El taller está en proceso de cierre.":"El taller tiene una operación en curso. Intentá nuevamente.",
              request.getRequestURI(),blocked?"CUENTA_EN_CIERRE":"CUENTA_NO_DISPONIBLE",null));
    }

    private Map<String, Object> optionalDetails(Map<String, Object> details) {
        return details == null || details.isEmpty() ? null : details;
    }

    private ResponseEntity<ApiError> archivoDemasiadoGrande(
            String message, HttpServletRequest request) {
        ApiError error = new ApiError(
                LocalDateTime.now(),
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Archivo demasiado grande",
                message,
                request.getRequestURI(),
                ArchivoDemasiadoGrandeException.CODE,
                null
        );
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }
}
