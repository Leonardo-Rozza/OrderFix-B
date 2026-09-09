package com.leonardorozza.mvgrreparacionesbackend.photos;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

/** Decode original bytes; MIME labels alone never establish image validity. No filesystem cache. */
public final class PrivatePhotoImageValidator {
    public static final int MAX_BYTES=8_000_000;
    public static final List<String> MIME_TYPES=List.of("image/jpeg","image/png");
    private static final long MAX_PIXELS=25_000_000;
    private static final int MAX_DIMENSION=12_000;
    private static final byte[] PNG_SIGNATURE={(byte)137,80,78,71,13,10,26,10};
    private PrivatePhotoImageValidator() { }
    public static void validate(String mime, byte[] bytes, long expectedLength, String sha) {
        if (mime==null || !MIME_TYPES.contains(mime) || bytes==null || bytes.length<12 || bytes.length>MAX_BYTES
                || bytes.length!=expectedLength || sha==null || !sha.matches("[0-9a-f]{64}")) throw PrivatePhotoException.invalid();
        try {
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),HexFormat.of().parseHex(sha)))
                throw PrivatePhotoException.invalid();
            // ImageIO tolerates some truncated containers and trailing data. Check boundaries first.
            if (mime.equals("image/png")) validatePngEnvelope(bytes);
            else validateJpegEnvelope(bytes);
            try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw PrivatePhotoException.invalid();
                ImageReader reader=readers.next();
                try {
                    String actual=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!(mime.equals("image/jpeg") && (actual.equals("jpeg") || actual.equals("jpg")))
                            && !(mime.equals("image/png") && actual.equals("png"))) throw PrivatePhotoException.invalid();
                    reader.setInput(stream,true,true);
                    int width=reader.getWidth(0), height=reader.getHeight(0);
                    if(width<1 || height<1 || width>MAX_DIMENSION || height>MAX_DIMENSION
                            || (long)width*height>MAX_PIXELS) throw PrivatePhotoException.invalid();
                    final boolean[] warning={false};
                    reader.addIIOReadWarningListener((source,message)->warning[0]=true);
                    var decoded=reader.read(0);
                    if(decoded==null) throw PrivatePhotoException.invalid();
                    try {
                        if(warning[0] || decoded.getWidth()!=width || decoded.getHeight()!=height)
                            throw PrivatePhotoException.invalid();
                    } finally { decoded.flush(); }
                } finally { reader.dispose(); }
            }
        } catch(PrivatePhotoException failure) { throw failure; }
        catch(Exception failure) { throw PrivatePhotoException.invalid(); }
    }

    private static void validatePngEnvelope(byte[] bytes) {
        if (!Arrays.equals(bytes,0,8,PNG_SIGNATURE,0,8)) throw PrivatePhotoException.invalid();
        int position=8;
        boolean imageData=false;
        while(position<=bytes.length-12) {
            long length=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,position,4).getInt());
            if(length>bytes.length-position-12L) throw PrivatePhotoException.invalid();
            int size=(int)length;
            String type=new String(bytes,position+4,4,java.nio.charset.StandardCharsets.US_ASCII);
            if(!type.matches("[A-Za-z]{4}") || (position==8 && (!type.equals("IHDR") || size!=13))
                    || (position!=8 && type.equals("IHDR")) || type.equals("acTL")) throw PrivatePhotoException.invalid();
            CRC32 crc=new CRC32();
            crc.update(bytes,position+4,4+size);
            long declared=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,position+8+size,4).getInt());
            if(crc.getValue()!=declared) throw PrivatePhotoException.invalid();
            position+=size+12;
            if(type.equals("IDAT")) imageData=true;
            if(type.equals("IEND")) {
                if(size!=0 || !imageData || position!=bytes.length) throw PrivatePhotoException.invalid();
                return;
            }
        }
        throw PrivatePhotoException.invalid();
    }

    private static void validateJpegEnvelope(byte[] bytes) {
        if((bytes[0]&255)!=255 || (bytes[1]&255)!=216) throw PrivatePhotoException.invalid();
        int position=2;
        boolean scan=false,sawScan=false;
        while(position<bytes.length) {
            if((bytes[position++]&255)!=255) {
                if(scan) continue;
                throw PrivatePhotoException.invalid();
            }
            while(position<bytes.length && (bytes[position]&255)==255) position++;
            if(position==bytes.length) break;
            int marker=bytes[position++]&255;
            if(scan && (marker==0 || (marker>=208 && marker<=215))) continue;
            scan=false;
            if(marker==217) {
                if(!sawScan || position!=bytes.length) throw PrivatePhotoException.invalid();
                return;
            }
            if(marker==0 || marker==216 || (marker>=208 && marker<=215)) throw PrivatePhotoException.invalid();
            if(marker==1) continue;
            if(position+2>bytes.length) break;
            int size=((bytes[position]&255)<<8)|(bytes[position+1]&255);
            if(size<2 || size>bytes.length-position) throw PrivatePhotoException.invalid();
            position+=size;
            if(marker==218) { scan=true; sawScan=true; }
        }
        throw PrivatePhotoException.invalid();
    }
}
