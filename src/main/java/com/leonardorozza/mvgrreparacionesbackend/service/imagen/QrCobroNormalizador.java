package com.leonardorozza.mvgrreparacionesbackend.service.imagen;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.ArchivoDemasiadoGrandeException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class QrCobroNormalizador {

    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_EJE = 1_024;
    public static final long MAX_PIXELES = 1_000_000L;

    private static final String QR_INVALIDO = "QR_COBRO_INVALIDO";
    private static final Set<String> FORMATOS_PERMITIDOS = Set.of("PNG", "JPEG", "JPG");

    public QrCobroNormalizado normalizar(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw invalido("El archivo QR está vacío.");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw demasiadoGrande();
        }

        byte[] entrada;
        try {
            entrada = archivo.getBytes();
        } catch (IOException ex) {
            throw invalido("No se pudo leer la imagen QR.");
        }
        if (entrada.length == 0) {
            throw invalido("El archivo QR está vacío.");
        }
        if (entrada.length > MAX_BYTES) {
            throw demasiadoGrande();
        }

        BufferedImage decodificada = decodificarValidandoCabecera(entrada);
        byte[] png = reencodePng(decodificada);
        if (png.length > MAX_BYTES) {
            throw new ArchivoDemasiadoGrandeException(
                    "La imagen QR normalizada supera el máximo permitido de 1 MiB.");
        }
        return new QrCobroNormalizado(png, sha256(png));
    }

    private BufferedImage decodificarValidandoCabecera(byte[] entrada) {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(entrada))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalido("El archivo no es una imagen PNG o JPEG válida.");
            }

            ImageReader reader = readers.next();
            try {
                String formato = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!FORMATOS_PERMITIDOS.contains(formato)) {
                    throw invalido("El archivo debe ser una imagen PNG o JPEG.");
                }

                // Las dimensiones se inspeccionan antes de decodificar todos los píxeles.
                reader.setInput(stream, false, true);
                int ancho = reader.getWidth(0);
                int alto = reader.getHeight(0);
                validarDimensiones(ancho, alto);

                BufferedImage imagen = reader.read(0);
                if (imagen == null || imagen.getWidth() != ancho || imagen.getHeight() != alto) {
                    throw invalido("No se pudo decodificar la imagen QR.");
                }
                return imagen;
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw invalido("El archivo no es una imagen PNG o JPEG válida.");
        }
    }

    private void validarDimensiones(int ancho, int alto) {
        long pixeles = (long) ancho * alto;
        if (ancho <= 0 || alto <= 0
                || ancho > MAX_EJE || alto > MAX_EJE
                || pixeles > MAX_PIXELES) {
            throw new BadRequestException(
                    QR_INVALIDO,
                    "La imagen QR debe medir como máximo 1024×1024 y no superar 1 megapíxel.",
                    Map.of(
                            "ancho", ancho,
                            "alto", alto,
                            "maximoPorEje", MAX_EJE,
                            "maximoPixeles", MAX_PIXELES));
        }
    }

    private byte[] reencodePng(BufferedImage origen) {
        boolean alpha = origen.getColorModel().hasAlpha();
        BufferedImage limpia = new BufferedImage(
                origen.getWidth(),
                origen.getHeight(),
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = limpia.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(origen, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(limpia, "PNG", output)) {
                throw invalido("No se pudo normalizar la imagen QR.");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw invalido("No se pudo normalizar la imagen QR.");
        }
    }

    private String sha256(byte[] contenido) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible en la JVM.", ex);
        }
    }

    private BadRequestException invalido(String message) {
        return new BadRequestException(
                QR_INVALIDO,
                message,
                Map.of("formatosPermitidos", "PNG,JPEG"));
    }

    private ArchivoDemasiadoGrandeException demasiadoGrande() {
        return new ArchivoDemasiadoGrandeException(
                "El archivo supera el máximo permitido de 1 MiB.");
    }

    public record QrCobroNormalizado(byte[] png, String sha256) {
        public QrCobroNormalizado {
            png = png.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }
    }
}
