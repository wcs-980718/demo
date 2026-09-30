package com.yiwei.midplat.fusion;
public class FusionUpstreamFault extends RuntimeException {
 private final int status;
 public FusionUpstreamFault(int status,String message){super(message);this.status=status;}
 public int status(){return status;}
}
