package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Fixed framed worker; source text cannot select commands, paths, classes or compiler phases. */
public final class SourceParserWorker {
  private SourceParserWorker() {}

  public static void main(String[] args)throws Exception {
    if(args.length!=0)throw new IllegalArgumentException("parser worker takes no arguments");
    try(var input=new DataInputStream(new BufferedInputStream(System.in));
        var output=new DataOutputStream(new BufferedOutputStream(System.out))) {
      for(int request=0;request<64;request++) {
        int length;
        try{length=input.readInt();}catch(EOFException ended){return;}
        boolean catalog=length==-2;if(catalog)length=input.readInt();
        if(length<0||length>(catalog?2_097_152:SourceFacts.SOURCE_BYTES))throw new IOException("parser input quota");
        byte[] source=input.readNBytes(length);if(source.length!=length)throw new EOFException("parser frame truncated");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        byte[] result=catalog?SourceCatalogWire.encode(SourceCatalogParser.parse(text)):
            SourceWire.encode(SourceJavaParser.parse(text));
        output.writeInt(result.length);output.write(result);output.flush();
      }
    }
  }
}
