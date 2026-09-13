package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePurpose;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class WorkshopClosureHttpRequests {
    static final String ROOT="/api/cuenta/cierre", REAUTH=ROOT+"/reauthenticaciones", COMMAND=ROOT+"/operaciones";
    enum Operation { READ, ISSUE, COMMAND }
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(1000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private WorkshopClosureHttpRequests() { }
    static AuthenticatedUserPrincipal actor() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal actor)
                || !actor.isEnabled() || actor.getUserId()==null || actor.getUserId()<=0 || actor.getTallerId()==null || actor.getTallerId()<=0)
            throw WorkshopClosureHttpException.unauthorized();
        if(actor.getAuthorities().size()!=1 || actor.getAuthorities().stream().noneMatch(role->"ROLE_ADMIN".equals(role.getAuthority())))
            throw WorkshopClosureHttpException.forbidden();
        if(!actor.isEmailVerificado()) throw new WorkshopClosureHttpException(HttpStatus.FORBIDDEN,"EMAIL_NO_VERIFICADO","Verificá tu email para continuar.");
        return actor;
    }
    static String path(HttpServletRequest request) {
        String path=request.getRequestURI(),context=request.getContextPath();
        if(path==null || context==null || (!context.isEmpty() && (!context.startsWith("/") || context.endsWith("/") || !path.startsWith(context+"/")))) return "";
        return path.substring(context.length());
    }
    static boolean matches(HttpServletRequest request) { String path=path(request); return path.equals(ROOT)||path.startsWith(ROOT+"/"); }
    static Operation operation(HttpServletRequest request) {
        String path=path(request); Operation result;
        if(path.equals(ROOT)) result=Operation.READ; else if(path.equals(REAUTH)) result=Operation.ISSUE;
        else if(path.equals(COMMAND)) result=Operation.COMMAND; else throw WorkshopClosureHttpException.missing();
        String method=result==Operation.READ?"GET":"POST";
        if(!method.equals(request.getMethod())) throw new WorkshopClosureHttpException(HttpStatus.METHOD_NOT_ALLOWED,"SOLICITUD_INVALIDA","El método de la solicitud no está permitido.");
        return result;
    }
    static void require(HttpServletRequest request,Operation expected) {
        if(operation(request)!=expected || request.getQueryString()!=null) throw WorkshopClosureHttpException.invalid();
        for(String name:List.of("Authorization","X-Reauth-Token","Content-Type","Content-Length","Transfer-Encoding","Content-Encoding")) singleHeader(request,name);
        if(singleHeader(request,"Content-Encoding")!=null) throw WorkshopClosureHttpException.invalid();
        String contentType=singleHeader(request,"Content-Type");
        if(expected!=Operation.READ || contentType!=null) {
            try {
                MediaType type=MediaType.parseMediaType(Objects.requireNonNull(contentType));
                if(!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                        || type.getParameters().keySet().stream().anyMatch(name->!"charset".equalsIgnoreCase(name))
                        || (type.getCharset()!=null && !StandardCharsets.UTF_8.equals(type.getCharset()))) throw new IllegalArgumentException();
            } catch(RuntimeException invalid) { throw new WorkshopClosureHttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"SOLICITUD_INVALIDA","La solicitud requiere JSON UTF-8."); }
        }
        if(expected!=Operation.COMMAND && singleHeader(request,"X-Reauth-Token")!=null) throw WorkshopClosureHttpException.invalid();
    }
    static String accessToken(HttpServletRequest request) {
        String value=singleHeader(request,"Authorization");
        if(value==null || !value.startsWith("Bearer ") || value.length()<=7 || value.length()>8199) throw WorkshopClosureHttpException.unauthorized();
        String token=value.substring(7);
        if(token.isBlank() || token.chars().anyMatch(character->Character.isWhitespace(character)||Character.isISOControl(character))) throw WorkshopClosureHttpException.unauthorized();
        return token;
    }
    static String proof(HttpServletRequest request) {
        String value=singleHeader(request,"X-Reauth-Token"); byte[] decoded=null;
        try {
            if(value==null || !value.matches("[A-Za-z0-9_-]{43}")) throw WorkshopClosureHttpException.proof();
            decoded=Base64.getUrlDecoder().decode(value);
            if(decoded.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw WorkshopClosureHttpException.proof();
            return value;
        } catch(IllegalArgumentException malformed) { throw WorkshopClosureHttpException.proof(); }
        finally { if(decoded!=null) Arrays.fill(decoded,(byte)0); }
    }
    static UUID uuid(String value) {
        try { if(value==null || value.length()!=36) throw new IllegalArgumentException(); UUID id=UUID.fromString(value); if(!id.toString().equals(value)) throw new IllegalArgumentException(); return id; }
        catch(IllegalArgumentException malformed) { throw WorkshopClosureHttpException.invalid(); }
    }
    static void emptyBody(HttpServletRequest request) {
        try { if(request.getContentLengthLong()>0 || request.getInputStream().read()!=-1) throw WorkshopClosureHttpException.invalid(); }
        catch(IOException failed) { throw WorkshopClosureHttpException.invalid(); }
    }
    static Reauthentication reauthentication(HttpServletRequest request) {
        JsonNode node=body(request,Set.of("passwordActual","proposito","operacionId","cierreReferencia"));
        String password=node.get("passwordActual").textValue();
        if(password.isBlank() || password.length()>100) throw WorkshopClosureHttpException.invalid();
        Identities ids=identities(node); return new Reauthentication(password,ids.purpose(),ids.operation(),ids.reference());
    }
    static Command command(HttpServletRequest request) {
        JsonNode node=body(request,Set.of("proposito","operacionId","cierreReferencia","confirmacion"));
        Identities ids=identities(node); String phrase=node.get("confirmacion").textValue();
        if(!(ids.purpose()==WorkshopClosurePurpose.CERRAR?"CERRAR MI TALLER":"RESTAURAR MI TALLER").equals(phrase)) throw WorkshopClosureHttpException.invalid();
        return new Command(ids.purpose(),ids.operation(),ids.reference(),phrase);
    }
    private static Identities identities(JsonNode node) {
        try {
            var purpose=WorkshopClosurePurpose.valueOf(node.get("proposito").textValue());
            UUID operation=uuid(node.get("operacionId").textValue()),reference=uuid(node.get("cierreReferencia").textValue());
            if(purpose==WorkshopClosurePurpose.CERRAR && !operation.equals(reference)) throw WorkshopClosureHttpException.invalid();
            return new Identities(purpose,operation,reference);
        } catch(IllegalArgumentException invalid) { throw WorkshopClosureHttpException.invalid(); }
    }
    private static JsonNode body(HttpServletRequest request,Set<String> fields) {
        byte[] bytes=null;
        try {
            if(request.getContentLengthLong()>4096) throw tooLarge(); bytes=request.getInputStream().readNBytes(4097); if(bytes.length>4096) throw tooLarge();
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node=JSON.readTree(text);
            if(node==null || !node.isObject() || node.size()!=fields.size() || fields.stream().anyMatch(field->!node.has(field)||!node.get(field).isTextual())) throw WorkshopClosureHttpException.invalid();
            return node;
        } catch(IOException | IllegalArgumentException invalid) { throw WorkshopClosureHttpException.invalid(); }
        finally { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
    }
    private static WorkshopClosureHttpException tooLarge() { return new WorkshopClosureHttpException(HttpStatus.PAYLOAD_TOO_LARGE,"SOLICITUD_INVALIDA","La solicitud excede el tamaño permitido."); }
    private static String singleHeader(HttpServletRequest request,String name) {
        var headers=request.getHeaders(name); if(headers==null || !headers.hasMoreElements()) return null;
        String first=headers.nextElement(); if(headers.hasMoreElements()) throw WorkshopClosureHttpException.invalid(); return first;
    }
    record Reauthentication(String passwordActual,WorkshopClosurePurpose purpose,UUID operationId,UUID reference) { @Override public String toString(){return "Reauthentication[redacted]";} }
    record Command(WorkshopClosurePurpose purpose,UUID operationId,UUID reference,String confirmation) { @Override public String toString(){return "Command[redacted]";} }
    private record Identities(WorkshopClosurePurpose purpose,UUID operation,UUID reference) { }
}
