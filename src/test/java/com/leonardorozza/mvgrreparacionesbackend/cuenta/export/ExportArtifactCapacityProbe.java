package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoImageValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipInputStream;

/** Manual opt-in capacity probe. No Test/IT suffix, real accounts, external assets or files containing plaintext. */
public final class ExportArtifactCapacityProbe {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int DATA_BYTES=64*1024*1024-8192;
    private static final long ACTOR=14,TALLER=7;
    private ExportArtifactCapacityProbe() { }
    public static void main(String[] arguments) throws Exception {
        if(arguments.length!=1 || !arguments[0].equals("--synthetic-pg16-only")) throw new IllegalArgumentException("Explicit synthetic probe flag required");
        long started=System.nanoTime();AtomicLong peak=new AtomicLong();
        var sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"capacity-heap-sampler");t.setDaemon(true);return t;});
        sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,20,TimeUnit.MILLISECONDS);
        try(var postgres=new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("ordenfix_export_capacity_probe")
                .withUsername("probe").withPassword("synthetic-capacity-only")) {
            postgres.start();
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
            jdbc.execute("CREATE TABLE export_capacity_probe(id integer PRIMARY KEY,snapshot_cipher bytea,archive_cipher bytea)");
            var fixture=fixture();ExportSnapshot snapshot=fixture.snapshot();
            var photos=fixture.photos();fixture=null;
            long photoBytes=photos.stream().mapToLong(ExportArtifactCodec.PhotoFile::size).sum();
            System.out.println("fixture data_bytes="+snapshot.files().stream().mapToLong(ExportFile::size).sum()+" photos="+photos.size()+" photo_bytes="+photoBytes+" max_heap_bytes="+Runtime.getRuntime().maxMemory());
            var codec=new ExportArtifactCodec(Map.of(1,Base64.getEncoder().encodeToString(new byte[32])),1);
            var context=new ExportArtifactCodec.Context(UUID.fromString("1e8d6111-d116-47f3-98a3-0dff4ed00001"),TALLER,ACTOR);
            byte[] snapshotCipher=codec.encryptSnapshot(context,snapshot);long snapshotSize=snapshotCipher.length;
            jdbc.update("INSERT INTO export_capacity_probe(id,snapshot_cipher) VALUES(1,?)",snapshotCipher);
            snapshot=null;snapshotCipher=null;
            phase("snapshot_committed",started);
            // Simulates a durable retry: use production JDBC bytea conversion and the persisted snapshot, never recapture.
            byte[] stored=jdbc.queryForObject("SELECT snapshot_cipher FROM export_capacity_probe WHERE id=?",byte[].class,1);
            ExportSnapshot recovered=codec.decryptSnapshot(context,stored);
            byte[] archive=codec.archive(context,recovered,photos);
            long archiveSize=archive.length;
            jdbc.update("UPDATE export_capacity_probe SET archive_cipher=?,snapshot_cipher=NULL WHERE id=1",archive);
            stored=null;recovered=null;photos=null;archive=null;
            phase("archive_committed",started);
            // A separate response lifecycle authenticates all ciphertext before inspecting the ZIP.
            byte[] fromDatabase=jdbc.queryForObject("SELECT archive_cipher FROM export_capacity_probe WHERE id=?",byte[].class,1);
            byte[] clear=codec.decryptArchive(context,fromDatabase);fromDatabase=null;
            var entries=new LinkedHashMap<String,Metadata>();byte[] manifest=null;long expanded=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(clear),StandardCharsets.UTF_8)) {
                java.util.zip.ZipEntry entry;byte[] buffer=new byte[64*1024];
                while((entry=zip.getNextEntry())!=null) {
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;int count;
                    ByteArrayOutputStream manifestBytes=entry.getName().equals("manifest.json")?new ByteArrayOutputStream():null;
                    while((count=zip.read(buffer))!=-1) {
                        size+=count;expanded+=count;digest.update(buffer,0,count);
                        if(manifestBytes!=null)manifestBytes.write(buffer,0,count);
                    }
                    if(manifestBytes!=null)manifest=manifestBytes.toByteArray();
                    if(entries.put(entry.getName(),new Metadata(size,HexFormat.of().formatHex(digest.digest())))!=null)throw new IllegalStateException("Duplicate entry");
                    zip.closeEntry();
                }
            }
            var catalog=JSON.readTree(Objects.requireNonNull(manifest));
            if(!catalog.path("exportacion_integral_completa").asBoolean() || !catalog.path("archivos_pendientes").isEmpty())throw new IllegalStateException("Incomplete archive");
            for(var file:catalog.path("archivos")) {
                var actual=entries.get(file.path("ruta").asText());
                if(actual==null || actual.bytes()!=file.path("bytes").asLong() || !actual.sha().equals(file.path("sha256").asText()))throw new IllegalStateException("Content mismatch");
            }
            if(entries.size()!=catalog.path("archivos").size()+1 || expanded>ExportArtifactCodec.MAX_ARCHIVE_CONTENT_BYTES)throw new IllegalStateException("Archive capacity mismatch");
            jdbc.update("DELETE FROM export_capacity_probe WHERE id=1");
            if(jdbc.queryForObject("SELECT count(*) FROM export_capacity_probe",Integer.class)!=0)throw new IllegalStateException("Cleanup failed");
            System.out.println("SUCCESS snapshot_cipher_bytes="+snapshotSize+" archive_cipher_bytes="+archiveSize+" zip_content_bytes="+expanded
                    +" entries="+entries.size()+" sampled_peak_heap_bytes="+peak.get()+" elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        } finally {sampler.shutdownNow();}
    }
    private static void phase(String name,long started) {
        System.out.println("phase="+name+" elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" heap_used_bytes="+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }
    private record Metadata(long bytes,String sha) { }
    private record Fixture(ExportSnapshot snapshot,List<ExportArtifactCodec.PhotoFile> photos) { }
    private static Fixture fixture() throws Exception {
        ImageIO.setUseCache(false);
        var image=new BufferedImage(1630,1520,BufferedImage.TYPE_INT_RGB);var random=new Random(20260912L);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,random.nextInt(0x1000000));
        byte[] png;
        try(var output=new ByteArrayOutputStream()) {ImageIO.write(image,"png",output);png=output.toByteArray();}
        image.flush();String sha=ExportFile.digest(png);
        PrivatePhotoImageValidator.validate("image/png",png,png.length,sha);
        if(png.length>8_000_000 || png.length*9L>ExportArtifactCodec.MAX_PHOTO_BYTES)throw new IllegalStateException("PNG fixture exceeded capacity");
        var photos=new ArrayList<ExportArtifactCodec.PhotoFile>();var pending=new ArrayList<ExportSnapshot.PendingPhoto>();
        var rows=JSON.createArrayNode();
        for(int index=0;index<9;index++) {
            UUID id=new UUID(0x35dd003630f048a5L,index+1);photos.add(new ExportArtifactCodec.PhotoFile(id,png));
            pending.add(new ExportSnapshot.PendingPhoto(id,"archivos/fotos/"+id+".png",sha,png.length));
            var row=rows.addObject();row.put("id",id.toString());row.put("taller_id",Long.toString(TALLER));row.put("reparacion_id","11");
            row.put("estado","ASOCIADA");row.put("archivo_estado","PENDIENTE_C");row.put("mime_type","image/png");row.put("bytes",Long.toString(png.length));row.put("sha256",sha);
        }
        Arrays.fill(png,(byte)0);
        var files=new ArrayList<ExportFile>();
        for(var query:ExportBusinessCatalog.queries())if(!query.category().equals("clientes"))files.add(new ExportFile("datos/"+query.category()+".json","application/json",0,"[]".getBytes(StandardCharsets.UTF_8)));
        for(String category:List.of("fotos_legacy","qr_cobro","aceptaciones_propias"))files.add(new ExportFile("datos/"+category+".json","application/json",0,"[]".getBytes(StandardCharsets.UTF_8)));
        files.add(new ExportFile("datos/fotos_privadas.json","application/json",9,JSON.writeValueAsBytes(rows)));
        files.add(new ExportFile("LEEME.txt","text/plain; charset=utf-8",-1,"Fixture local B".getBytes(StandardCharsets.UTF_8)));
        int size=DATA_BYTES-files.stream().mapToInt(ExportFile::size).sum();byte[] data=new byte[size];data[0]='[';int position=1,count=0;
        byte[] alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.US_ASCII);
        while(position<data.length-1) {
            String prefix=(count==0?"":",")+"{\"id\":\""+(count+1)+"\",\"nombre\":\"";
            byte[] head=prefix.getBytes(StandardCharsets.US_ASCII);int available=data.length-position-head.length-3;
            if(available<0){while(position<data.length-1)data[position++]=' ';break;}
            System.arraycopy(head,0,data,position,head.length);position+=head.length;
            int text=Math.min(16384,available);
            for(int index=0;index<text;index++)data[position++]=alphabet[random.nextInt(alphabet.length)];
            data[position++]='"';data[position++]='}';count++;
        }
        data[data.length-1]=']';
        files.add(new ExportFile("datos/clientes.json","application/json",count,data));Arrays.fill(data,(byte)0);
        if(count>50_000)throw new IllegalStateException("Row cap exceeded");
        return new Fixture(new ExportSnapshot(ACTOR,TALLER,Instant.parse("2026-09-12T15:00:00Z"),files,pending),List.copyOf(photos));
    }
}
