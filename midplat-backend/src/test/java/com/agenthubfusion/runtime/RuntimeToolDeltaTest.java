package com.agenthubfusion.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeToolDeltaTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode tool(){ObjectNode value=json.createObjectNode().put("id","");value.putObject("function").put("name","").put("arguments","");return value;}
    private ObjectNode delta(String id,String name,String arguments){ObjectNode value=json.createObjectNode().put("id",id);value.putObject("function").put("name",name).put("arguments",arguments);return value;}
    @Test void repeatedIdentifiersAndNamesStayStableWhileArgumentFragmentsAlwaysAppend()throws Exception{
        ObjectNode value=tool();
        RuntimeMain.mergeToolFragment(value,delta("chatcmpl-tool-123","lookup","{\"query\":\""));
        RuntimeMain.mergeToolFragment(value,delta("chatcmpl-tool-123","lookup","a"));
        RuntimeMain.mergeToolFragment(value,delta("chatcmpl-tool-123","lookup","a"));
        RuntimeMain.mergeToolFragment(value,delta("chatcmpl-tool-123","lookup","\"}"));
        assertEquals("chatcmpl-tool-123",value.path("id").asText());
        assertEquals("lookup",value.path("function").path("name").asText());
        assertEquals("aa",json.readTree(value.path("function").path("arguments").asText()).path("query").asText());
    }
    @Test void identifierAndNameFragmentsCanStillBeAssembled()throws Exception{
        ObjectNode value=tool();RuntimeMain.mergeToolFragment(value,delta("call-","look","{"));
        RuntimeMain.mergeToolFragment(value,delta("123","up","}"));
        assertEquals("call-123",value.path("id").asText());assertEquals("lookup",value.path("function").path("name").asText());
    }
}
