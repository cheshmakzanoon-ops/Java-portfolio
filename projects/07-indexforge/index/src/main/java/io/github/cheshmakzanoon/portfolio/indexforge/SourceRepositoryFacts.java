package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Canonical repository partition; random generation identity is bound by its outer commit. */
public final class SourceRepositoryFacts {
  private static final int MAGIC=0x49465347;
  public static final int SERIALIZED_LIMIT=33_554_432;
  public static final int DECODED_LIMIT=8_388_608;

  public record Snapshot(String tenant,String repository,boolean inventoryObserved,List<SourceSymbolGraph.Document> documents) {
    public Snapshot {
      if(!tenant.matches("[a-zA-Z0-9_-]{1,64}")||!repository.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("source graph scope");
      documents=List.copyOf(documents);
      if(documents.size()>100_000||!inventoryObserved&&!documents.isEmpty())throw new IllegalArgumentException("partial repository graph forbidden");
    }
  }

  /** One repository accumulator is live while the sorted generation is built. */
  public static final class Builder {
    private final String tenant,repository;
    private final List<SourceSymbolGraph.Document> documents=new ArrayList<>();
    private int decoded,members,calls,encoded;
    private boolean observed=true;
    public Builder(String tenant,String repository){this.tenant=tenant;this.repository=repository;new Snapshot(tenant,repository,true,List.of());}
    public void add(SourceDocument source,SourceFacts.Unit facts)throws IOException {
      if(!source.tenant().equals(tenant)||!source.repository().equals(repository))throw new IOException("source graph repository mismatch");
      if(!observed)return;
      byte[] bytes=SourceWire.encode(facts);
      decoded+=SourceWire.decodedBytes(bytes);encoded+=bytes.length+3*source.key().length()+32;
      members+=facts.members().size();for(var member:facts.members())calls+=member.calls().size();
      if(decoded>DECODED_LIMIT||encoded>SERIALIZED_LIMIT-1024||members>65_536||calls>262_144||documents.size()==100_000) {
        observed=false;documents.clear();return;
      }
      documents.add(new SourceSymbolGraph.Document(source.key(),facts));
    }
    public Snapshot finish(){return new Snapshot(tenant,repository,observed,documents);}
  }

  private SourceRepositoryFacts() {}
  public static String fileName(String tenant,String repository) {
    new Snapshot(tenant,repository,true,List.of());return "source-graph-"+Digests.sha256(tenant+"/"+repository)+".bin";
  }

  public static void write(Path generation,Snapshot snapshot)throws IOException {
    var bytes=new ByteArrayOutputStream();
    try(var out=new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);out.writeUTF(SourceSymbolGraph.SCHEMA);out.writeUTF(SourceFacts.SCHEMA);
      out.writeUTF(snapshot.tenant());out.writeUTF(snapshot.repository());out.writeBoolean(snapshot.inventoryObserved());out.writeInt(snapshot.documents().size());
      for(var document:snapshot.documents().stream().sorted(Comparator.comparing(SourceSymbolGraph.Document::key)).toList()) {
        byte[] unit=SourceWire.encode(document.facts());out.writeUTF(document.key());out.writeInt(unit.length);out.write(unit);
        if(bytes.size()>SERIALIZED_LIMIT-32)throw new IOException("source graph serialization quota");
      }
    }
    byte[] body=bytes.toByteArray();bytes.write(HexFormat.of().parseHex(Digests.sha256(body)));
    DurableFiles.atomicWrite(generation.resolve(fileName(snapshot.tenant(),snapshot.repository())),bytes.toByteArray());
  }

  public static Snapshot read(Path generation,String tenant,String repository)throws IOException {
    Path file=generation.resolve(fileName(tenant,repository));
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException("source graph regular file required");
    byte[] data;
    try(var input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){data=input.readNBytes(SERIALIZED_LIMIT+1);}
    if(data.length<32||data.length>SERIALIZED_LIMIT)throw new IOException("source graph file quota");
    byte[] body=Arrays.copyOf(data,data.length-32);
    if(!MessageDigest.isEqual(HexFormat.of().parseHex(Digests.sha256(body)),Arrays.copyOfRange(data,data.length-32,data.length)))throw new IOException("source graph checksum");
    try(var in=new DataInputStream(new ByteArrayInputStream(body))) {
      if(in.readInt()!=MAGIC||!in.readUTF().equals(SourceSymbolGraph.SCHEMA)||!in.readUTF().equals(SourceFacts.SCHEMA)
          ||!in.readUTF().equals(tenant)||!in.readUTF().equals(repository))throw new IOException("source graph binding");
      boolean observed=in.readBoolean();int count=in.readInt();
      if(count<0||count>100_000||!observed&&count!=0)throw new IOException("source graph count");
      List<SourceSymbolGraph.Document> documents=new ArrayList<>();String previous="";long decoded=0;
      for(int n=0;n<count;n++) {
        String key=in.readUTF();int length=in.readInt();
        if(!key.startsWith(tenant+"/"+repository+"/")||key.compareTo(previous)<=0||key.length()>2048||length<36||length>SourceFacts.SUMMARY_BYTES)
          throw new IOException("source graph unit identity/quota");
        previous=key;byte[] unit=in.readNBytes(length);if(unit.length!=length)throw new EOFException("source graph unit truncated");
        decoded+=SourceWire.decodedBytes(unit);
        if(decoded>DECODED_LIMIT)throw new IOException("source graph decoded work quota");
        documents.add(new SourceSymbolGraph.Document(key,SourceWire.decode(unit)));
      }
      if(in.read()!=-1)throw new IOException("source graph trailing bytes");
      return new Snapshot(tenant,repository,observed,documents);
    }catch(IllegalArgumentException invalid){throw new IOException("invalid source graph",invalid);}
  }
}
