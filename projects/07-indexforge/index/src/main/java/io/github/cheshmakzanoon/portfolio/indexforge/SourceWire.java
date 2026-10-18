package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Explicit bounded binary protocol; Java object deserialization is never used. */
public final class SourceWire {
  private SourceWire() {}

  public static byte[] encode(SourceFacts.Unit unit) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(new LimitedOutput(bytes, SourceFacts.RAW_SUMMARY_BYTES))) {
      out.writeUTF(SourceFacts.SCHEMA); out.writeByte(unit.status().ordinal());
      out.writeUTF(unit.packageName()); strings(out, unit.imports());
      out.writeInt(unit.types().size());
      for (var t : unit.types()) {
        out.writeUTF(t.name()); out.writeBoolean(t.closed()); out.writeBoolean(t.unknownSupertype());
        bindings(out, t.fields()); out.writeInt(t.recordComponents().size());
        for (var e : t.recordComponents().entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
      }
      out.writeInt(unit.members().size());
      for (var m : unit.members()) {
        out.writeUTF(m.owner()); out.writeUTF(m.name()); out.writeUTF(m.returnType());
        bindings(out, m.parameters()); bindings(out, m.locals()); strings(out, m.identifiers());
        out.writeInt(m.calls().size());
        for (var c : m.calls()) { expression(out,c.expression(),0); out.writeInt(c.position()); out.writeInt(c.block()); }
        out.writeInt(m.guards().size());
        for (var g : m.guards()) { expression(out,g.condition(),0);out.writeInt(g.end());out.writeInt(g.block());out.writeUTF(g.terminal()); }
        out.writeInt(m.position()); out.writeBoolean(m.privateMember()); out.writeBoolean(m.staticMember()); out.writeBoolean(m.bindingUnknown());
      }
    }
    byte[] raw=bytes.toByteArray();bytes.reset();
    var compressor=new Deflater(Deflater.BEST_SPEED);
    try {
      compressor.setInput(raw);compressor.finish();byte[] buffer=new byte[4096];
      var limited=new LimitedOutput(bytes,SourceFacts.SUMMARY_BYTES-32);
      while(!compressor.finished()){int n=compressor.deflate(buffer);if(n==0)throw new IOException("source compression stalled");limited.write(buffer,0,n);}
    } finally {compressor.end();}
    byte[] body = bytes.toByteArray(); bytes.write(hash(body)); return bytes.toByteArray();
  }

  public static SourceFacts.Unit decode(byte[] data) throws IOException {
    if (data.length < 32 || data.length > SourceFacts.SUMMARY_BYTES) throw new IOException("source summary byte quota");
    byte[] body = Arrays.copyOf(data,data.length-32);
    if (!MessageDigest.isEqual(hash(body), Arrays.copyOfRange(data,data.length-32,data.length)))
      throw new IOException("source summary checksum");
    var inflater=new Inflater();var raw=new ByteArrayOutputStream();
    try {
      inflater.setInput(body);byte[] buffer=new byte[4096];
      var limited=new LimitedOutput(raw,SourceFacts.RAW_SUMMARY_BYTES);
      while(!inflater.finished()) {
        int n=inflater.inflate(buffer);
        if(n==0)throw new IOException("source compressed frame truncated or dictionary required");
        limited.write(buffer,0,n);
      }
      if(inflater.getRemaining()!=0)throw new IOException("trailing compressed source bytes");
    }catch(DataFormatException invalid){throw new IOException("invalid compressed source frame",invalid);}
    finally{inflater.end();}
    try(var in = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()))) {
      if (!in.readUTF().equals(SourceFacts.SCHEMA)) throw new IOException("source summary schema");
      var status=SourceFacts.Status.values()[count(in.readUnsignedByte(), SourceFacts.Status.values().length-1)];
      String pkg=in.readUTF();var imports=strings(in,64);
      List<SourceFacts.Type> types=new ArrayList<>();
      for(int n=count(in.readInt(),128);n>0;n--) {
        String name=in.readUTF();boolean closed=in.readBoolean(),sup=in.readBoolean();var fields=bindings(in);
        Map<String,String> components=new TreeMap<>();
        for(int c=count(in.readInt(),64);c>0;c--) if(components.put(in.readUTF(),in.readUTF())!=null)throw new IOException("duplicate component");
        types.add(new SourceFacts.Type(name,closed,sup,fields,components));
      }
      List<SourceFacts.Member> members=new ArrayList<>();
      for(int n=count(in.readInt(),128);n>0;n--) {
        String owner=in.readUTF(),name=in.readUTF(),returns=in.readUTF();var params=bindings(in);var locals=bindings(in);
        Set<String> identifiers=new TreeSet<>(strings(in,128));List<SourceFacts.Invocation> calls=new ArrayList<>();
        for(int c=count(in.readInt(),128);c>0;c--)calls.add(new SourceFacts.Invocation(expression(in,0),in.readInt(),in.readInt()));
        List<SourceFacts.Guard> guards=new ArrayList<>();
        for(int g=count(in.readInt(),128);g>0;g--)guards.add(new SourceFacts.Guard(expression(in,0),in.readInt(),in.readInt(),in.readUTF()));
        members.add(new SourceFacts.Member(owner,name,returns,params,locals,identifiers,calls,guards,in.readInt(),in.readBoolean(),in.readBoolean(),in.readBoolean()));
      }
      if(in.read()!=-1)throw new IOException("trailing source facts");
      return new SourceFacts.Unit(status,pkg,imports,types,members);
    } catch (IllegalArgumentException invalid) { throw new IOException("invalid source facts",invalid); }
  }

  private static void bindings(DataOutputStream out,List<SourceFacts.Binding> bindings)throws IOException {
    out.writeInt(bindings.size());for(var b:bindings){out.writeUTF(b.name());out.writeUTF(b.type());expression(out,b.initializer(),0);out.writeBoolean(b.ambiguous());}
  }
  private static List<SourceFacts.Binding> bindings(DataInputStream in)throws IOException {
    List<SourceFacts.Binding> out=new ArrayList<>();for(int n=count(in.readInt(),64);n>0;n--)out.add(new SourceFacts.Binding(in.readUTF(),in.readUTF(),expression(in,0),in.readBoolean()));return out;
  }
  private static void strings(DataOutputStream out,Collection<String> values)throws IOException {out.writeInt(values.size());for(String s:values)out.writeUTF(s);}
  private static List<String> strings(DataInputStream in,int max)throws IOException {List<String> out=new ArrayList<>();for(int n=count(in.readInt(),max);n>0;n--)out.add(in.readUTF());return out;}
  private static void expression(DataOutputStream out,SourceFacts.Expression e,int depth)throws IOException {
    if(depth>8)throw new IOException("source expression depth");out.writeUTF(e.kind());out.writeUTF(e.text());out.writeInt(e.children().size());for(var c:e.children())expression(out,c,depth+1);
  }
  private static SourceFacts.Expression expression(DataInputStream in,int depth)throws IOException {
    if(depth>8)throw new IOException("source expression depth");String kind=in.readUTF(),text=in.readUTF();List<SourceFacts.Expression> children=new ArrayList<>();for(int n=count(in.readInt(),65);n>0;n--)children.add(expression(in,depth+1));return new SourceFacts.Expression(kind,text,children);
  }
  private static int count(int n,int max)throws IOException {if(n<0||n>max)throw new IOException("source count quota");return n;}
  private static byte[] hash(byte[] data) {try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}

  private static final class LimitedOutput extends OutputStream {
    private final OutputStream target;private final int max;private int count;
    LimitedOutput(OutputStream target,int max){this.target=target;this.max=max;}
    @Override public void write(int b)throws IOException {if(++count>max)throw new IOException("source encoded quota");target.write(b);}
    @Override public void write(byte[] b,int offset,int length)throws IOException {if(length<0||count>max-length)throw new IOException("source encoded quota");count+=length;target.write(b,offset,length);}
  }
}
