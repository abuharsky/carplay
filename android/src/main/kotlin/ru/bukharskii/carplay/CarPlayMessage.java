package ru.bukharskii.carplay;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class CarPlayMessage {
    public final CarPlayMessageHeader header;
    public final ByteBuffer data;

    public CarPlayMessage(CarPlayMessageHeader header, ByteBuffer data){
        this.header = header;
        this.data = data;
    }
}
