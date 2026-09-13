package main.java.me.dniym.audit.playerdata;

import java.io.*;

/** Validate lengths before the NBT library can allocate attacker-controlled arrays. */
final class NbtBounds {
    private final DataInputStream in;
    private int nodes;
    private NbtBounds(byte[] bytes){in=new DataInputStream(new ByteArrayInputStream(bytes));}
    static void validate(byte[] bytes)throws IOException{
        NbtBounds parser=new NbtBounds(bytes);
        int type=parser.in.readUnsignedByte();if(type!=10)throw new IOException("Root NBT invalido");
        parser.string();parser.payload(type,0);
        if(parser.in.available()!=0)throw new IOException("NBT com dados extras");
    }
    private void skip(long length)throws IOException{
        if(length<0||length>in.available())throw new IOException("Tamanho NBT invalido");
        in.skipNBytes(length);
    }
    private void string()throws IOException{skip(in.readUnsignedShort());}
    private int count()throws IOException{int size=in.readInt();if(size<0)throw new IOException("Contagem NBT negativa");return size;}
    private void payload(int type,int depth)throws IOException{
        if(depth>32||++nodes>100000)throw new IOException("Limite estrutural NBT excedido");
        switch(type){
            case 1->skip(1);case 2->skip(2);case 3,5->skip(4);case 4,6->skip(8);
            case 7->skip(count());case 8->string();
            case 9->{int child=in.readUnsignedByte(),size=count();if(size>100000||child==0&&size!=0)throw new IOException("Lista NBT invalida");for(int i=0;i<size;i++)payload(child,depth+1);}
            case 10->{int child;while((child=in.readUnsignedByte())!=0){string();payload(child,depth+1);}}
            case 11->skip((long)count()*4);case 12->skip((long)count()*8);
            default->throw new IOException("Tag NBT desconhecida");
        }
    }
}
