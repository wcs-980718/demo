package com.agenthubfusion;
public class FusionFault extends RuntimeException {
 final int status;
 public FusionFault(int status,String message){super(message);this.status=status;}
}
