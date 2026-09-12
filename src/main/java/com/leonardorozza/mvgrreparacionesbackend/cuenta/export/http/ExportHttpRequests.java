package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.util.*;

public final class ExportHttpRequests {
    static final String REAUTH="/api/cuenta/reauthenticaciones", EXPORTS="/api/exportaciones", EXCEL="/api/export/excel";
    public enum Operation { ISSUE, REQUEST, READ, DOWNLOAD, RETIRED }
    record Reauthentication(String passwordActual, ExportReauthenticationPurpose proposito) {
        @Override public String toString() { return "Reauthentication[REDACTED]"; }
    }
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(1000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ExportHttpRequests() { }

    static String path(HttpServletRequest request) {
        String path=request.getRequestURI(), context=request.getContextPath();
        if(path==null || context==null || (!context.isEmpty() && (!context.startsWith("/") || context.endsWith("/") || !path.startsWith(context+"/")))) return "";
        return path.substring(context.length());
    }
    static boolean matches(HttpServletRequest request) {
        String path=path(request);
        return path.equals(REAUTH) || path.equals(EXCEL) || path.equals(EXPORTS) || path.startsWith(EXPORTS+"/");
    }
    static Operation operation(HttpServletRequest request) {
        String path=path(request), method=request.getMethod();
        if(path.equals(REAUTH)) { method(method,"POST"); return Operation.ISSUE; }
        if(path.equals(EXCEL)) {
            if(method.equals("GET")) return Operation.RETIRED;
            method(method,"POST"); return Operation.DOWNLOAD;
        }
        if(path.equals(EXPORTS)) { method(method,"POST"); return Operation.REQUEST; }
        if(path.equals(EXPORTS+"/actual")) { method(method,"GET"); return Operation.READ; }
        if(path.startsWith(EXPORTS+"/")) {
            String suffix=path.substring(EXPORTS.length()+1);
            if(suffix.endsWith("/archivo")) {
                canonicalUuid(suffix.substring(0,suffix.length()-8)); method(method,"POST"); return Operation.DOWNLOAD;
            }
            canonicalUuid(suffix); method(method,"GET"); return Operation.READ;
        }
        throw ExportHttpException.missing();
    }
    public static void require(HttpServletRequest request,Operation expected) {
        if(operation(request)!=expected) throw ExportHttpException.invalid();
        if(request.getQueryString()!=null && !request.getQueryString().isEmpty()) throw ExportHttpException.invalid();
    }
    private static void method(String actual,String expected) {
        if(!expected.equals(actual)) throw new ExportHttpException(HttpStatus.METHOD_NOT_ALLOWED,"SOLICITUD_INVALIDA","El método de la solicitud no está permitido.");
    }
    public static String accessToken(HttpServletRequest request) {
        String value=singleHeader(request,"Authorization");
        if(value==null || !value.startsWith("Bearer ") || value.length()<=7 || value.length()>8199) throw ExportHttpException.unauthorized();
        String token=value.substring(7);
        if(token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) throw ExportHttpException.unauthorized();
        return token;
    }
    public static String proof(HttpServletRequest request) {
        String value=singleHeader(request,"X-Reauth-Token");
        try {
            if(value==null || !value.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            byte[] decoded=Base64.getUrlDecoder().decode(value);
            try {
                if(decoded.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw new IllegalArgumentException();
            } finally { Arrays.fill(decoded,(byte)0); }
            return value;
        } catch(IllegalArgumentException failure) {
            throw new ExportHttpException(HttpStatus.BAD_REQUEST,"REAUTENTICACION_INVALIDA","La confirmación no es válida o ya venció. Volvé a confirmar tu contraseña.");
        }
    }
    static UUID requestKey(HttpServletRequest request) { return canonicalUuid(singleHeader(request,"Idempotency-Key")); }
    static UUID canonicalUuid(String value) {
        try {
            if(value==null || value.length()!=36) throw new IllegalArgumentException();
            UUID id=UUID.fromString(value); if(!id.toString().equals(value)) throw new IllegalArgumentException(); return id;
        } catch(IllegalArgumentException failure) { throw ExportHttpException.invalid(); }
    }
    public static void emptyBody(HttpServletRequest request) {
        try {
            if(request.getContentLengthLong()>0 || request.getInputStream().read()!=-1) throw ExportHttpException.invalid();
        } catch(IOException failure) { throw ExportHttpException.invalid(); }
    }
    static Reauthentication reauthentication(HttpServletRequest request) {
        byte[] bytes=null;
        try {
            if(request.getContentLengthLong()>4096) throw ExportHttpException.tooLarge();
            bytes=request.getInputStream().readNBytes(4097);
            if(bytes.length>4096) throw ExportHttpException.tooLarge();
            var node=JSON.readTree(bytes);
            if(node==null || !node.isObject() || node.size()!=2 || !node.has("passwordActual") || !node.has("proposito")
                    || !node.get("passwordActual").isTextual() || !node.get("proposito").isTextual()) throw ExportHttpException.invalid();
            String password=node.get("passwordActual").textValue();
            if(password.isBlank() || password.length()>100) throw ExportHttpException.invalid();
            return new Reauthentication(password,ExportReauthenticationPurpose.valueOf(node.get("proposito").textValue()));
        } catch(IOException | IllegalArgumentException failure) { throw ExportHttpException.invalid(); }
        finally { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
    }
    private static String singleHeader(HttpServletRequest request,String name) {
        var values=request.getHeaders(name);
        if(values==null || !values.hasMoreElements()) return null;
        String value=values.nextElement(); if(values.hasMoreElements()) throw ExportHttpException.invalid(); return value;
    }
}
