package com.leonardorozza.mvgrreparacionesbackend.photos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivatePhotoImageValidatorTest {
    @ParameterizedTest @CsvSource({"png,image/png", "jpeg,image/jpeg"})
    void acceptsDecodedOriginalAndDoesNotChangeBytes(String format, String mime) throws Exception {
        byte[] bytes=image(format,false), original=bytes.clone();
        assertThatCode(()->PrivatePhotoImageValidator.validate(mime,bytes,bytes.length,sha(bytes))).doesNotThrowAnyException();
        assertThat(bytes).containsExactly(original);
    }

    @Test void acceptsProgressiveJpegWithMultipleScans() throws Exception {
        byte[] bytes=image("jpeg",true);
        assertThatCode(()->PrivatePhotoImageValidator.validate("image/jpeg",bytes,bytes.length,sha(bytes))).doesNotThrowAnyException();
    }

    @Test void requiresExactManifestBeforeDecoding() throws Exception {
        byte[] bytes=image("png",false);
        invalid("image/jpeg",bytes,bytes.length,sha(bytes));
        invalid("image/png",bytes,bytes.length+1,sha(bytes));
        invalid("image/png",bytes,bytes.length,"0".repeat(64));
        invalid("image/png",bytes,bytes.length,sha(bytes).toUpperCase(java.util.Locale.ROOT));
        invalid("image/png",bytes,bytes.length,null);
        invalid(null,bytes,bytes.length,sha(bytes));
        invalid("image/webp",bytes,bytes.length,sha(bytes));
        invalid("image/png",null,0,"0".repeat(64));
        invalid("image/png",new byte[0],0,sha(new byte[0]));
    }

    @Test void rejectsOversizeWithoutReadingAnImage() throws Exception {
        byte[] bytes=new byte[PrivatePhotoImageValidator.MAX_BYTES+1];
        invalid("image/png",bytes,bytes.length,sha(bytes));
    }

    @ParameterizedTest @ValueSource(strings={"png","jpeg"})
    void rejectsTruncationAndTrailingDataEvenWithMatchingManifest(String format) throws Exception {
        byte[] image=image(format,false);
        String mime=format.equals("png")?"image/png":"image/jpeg";
        byte[] truncated=Arrays.copyOf(image,image.length-2);
        invalid(mime,truncated,truncated.length,sha(truncated));
        byte[] trailing=Arrays.copyOf(image,image.length+2);
        trailing[trailing.length-2]=(byte)255; trailing[trailing.length-1]=(byte)217;
        invalid(mime,trailing,trailing.length,sha(trailing));
    }

    @Test void rejectsPngBadCrcAndUnsignedChunkOverflow() throws Exception {
        byte[] bytes=image("png",false);
        bytes[29]^=1;
        invalid("image/png",bytes,bytes.length,sha(bytes));
        bytes=image("png",false);
        ByteBuffer.wrap(bytes,8,4).putInt(-1);
        invalid("image/png",bytes,bytes.length,sha(bytes));
    }

    @ParameterizedTest @CsvSource({"6000,6000", "12001,1", "1,12001", "0,10", "-1,10"})
    void rejectsExcessiveOrInvalidDimensionsBeforeRasterAllocation(int width,int height) throws Exception {
        byte[] bytes=image("png",false);
        ByteBuffer.wrap(bytes,16,8).putInt(width).putInt(height);
        CRC32 crc=new CRC32(); crc.update(bytes,12,17);
        ByteBuffer.wrap(bytes,29,4).putInt((int)crc.getValue());
        invalid("image/png",bytes,bytes.length,sha(bytes));
    }

    @Test void rejectsCompressedDataCorruptionEvenWithCorrectContainerCrc() throws Exception {
        byte[] bytes=image("png",false);
        int position=33;
        while(position<bytes.length) {
            int length=ByteBuffer.wrap(bytes,position,4).getInt();
            String type=new String(bytes,position+4,4,java.nio.charset.StandardCharsets.US_ASCII);
            if(type.equals("IDAT")) {
                Arrays.fill(bytes,position+8,position+8+length,(byte)0);
                CRC32 crc=new CRC32(); crc.update(bytes,position+4,length+4);
                ByteBuffer.wrap(bytes,position+8+length,4).putInt((int)crc.getValue());
                invalid("image/png",bytes,bytes.length,sha(bytes));
                return;
            }
            position+=length+12;
        }
        throw new AssertionError("Synthetic PNG did not contain image data");
    }

    private static void invalid(String mime,byte[] bytes,long length,String sha) {
        assertThatThrownBy(()->PrivatePhotoImageValidator.validate(mime,bytes,length,sha))
                .isInstanceOfSatisfying(PrivatePhotoException.class,error->{
                    assertThat(error.code()).isEqualTo("FOTO_INVALIDA");
                    assertThat(error.status().value()).isEqualTo(400);
                    assertThat(error.getCause()).isNull();
                });
    }

    private static byte[] image(String format,boolean progressive) throws Exception {
        BufferedImage image=new BufferedImage(12,9,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) image.setRGB(x,y,(x*17)<<16|(y*23)<<8|127);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        var writer=ImageIO.getImageWritersByFormatName(format).next();
        try(var stream=new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            var parameters=writer.getDefaultWriteParam();
            if(progressive) parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null,new IIOImage(image,null,null),parameters);
        } finally { writer.dispose(); image.flush(); }
        return output.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
