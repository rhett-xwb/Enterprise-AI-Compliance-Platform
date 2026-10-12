package com.enterprise.compliance;

import com.enterprise.compliance.document.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyChunkerTest {
    @Test void clausesAreSeparatedOffsetsExactAndLongClausesOverlap() {
        String text="第一条 出差审批\n"+"出差前必须申请。".repeat(100)+"\n第二条 报销\n凭发票报销。";
        var source=new ParsingStore.Source(0,1,"第 1 页",null,text,"TEXT",null,List.of());
        var chunks=PolicyChunker.split(List.of(source));
        assertTrue(chunks.size()>2);
        for(var c:chunks) {
            assertTrue(c.text().length()<=400);
            assertEquals(text.substring(c.start(),c.end()),c.text());
            assertEquals(1,c.pageNumber());
            assertFalse(c.text().contains("第一条") && c.text().contains("第二条"));
        }
        assertEquals(60,chunks.getFirst().end()-chunks.get(1).start());
        assertTrue(chunks.getLast().sectionTitle().startsWith("第二条"));
    }
    @Test void docxHasParagraphLocationAndNoInventedPage() {
        var result=PolicyChunker.split(List.of(new ParsingStore.Source(0,null,"段落 1","差旅","必须先申请","DOCX",null,List.of())));
        assertNull(result.getFirst().pageNumber()); assertEquals("段落 1",result.getFirst().location());
        assertThrows(IllegalArgumentException.class,()->PolicyChunker.split(List.of(new ParsingStore.Source(0,1,"页",null," ","TEXT",null,List.of()))));
    }
}
