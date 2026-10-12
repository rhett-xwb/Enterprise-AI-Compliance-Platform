package com.enterprise.compliance.document;

import java.util.*;
import java.util.regex.Pattern;

/** Source-local offsets preserve PDF pages and DOCX paragraphs without inventing pagination. */
public final class PolicyChunker {
    private static final Pattern HEADING=Pattern.compile("(?m)^(?:第[一二三四五六七八九十百0-9]+[章节条]|[0-9]+[.、]).*$");
    public record Slice(int sourceIndex,Integer pageNumber,String location,String sectionTitle,int start,int end,String text) {}
    public static List<Slice> split(List<ParsingStore.Source> sources) {
        var result=new ArrayList<Slice>();
        for(var source:sources) {
            String text=source.text(); if(text.isBlank()) continue;
            var boundaries=new ArrayList<Integer>(); boundaries.add(0);
            var headings=HEADING.matcher(text);
            while(headings.find()) if(headings.start()>0) boundaries.add(headings.start());
            boundaries.add(text.length());
            for(int b=0;b<boundaries.size()-1;b++) {
                int begin=boundaries.get(b),limit=boundaries.get(b+1);
                String section=source.sectionTitle(); var heading=HEADING.matcher(text.substring(begin,limit));
                if(heading.find() && heading.start()==0) section=heading.group().substring(0,Math.min(255,heading.group().length()));
                int start=begin;
                while(start<limit) {
                    int end=Math.min(start+400,limit);
                    if(end<limit) {
                        for(int p=end-1;p>start+200;p--) if("。；\n!?！？".indexOf(text.charAt(p))>=0) { end=p+1; break; }
                        if(Character.isHighSurrogate(text.charAt(end-1))) end--;
                    }
                    String part=text.substring(start,end);
                    if(!part.isBlank()) result.add(new Slice(source.sourceIndex(),source.pageNumber(),source.location(),section,start,end,part));
                    if(result.size()>10000) throw new IllegalArgumentException("CHUNK_LIMIT");
                    if(end==limit) break;
                    start=Math.max(start+1,end-60);
                    if(Character.isLowSurrogate(text.charAt(start))) start++;
                }
            }
        }
        if(result.isEmpty()) throw new IllegalArgumentException("EMPTY_DOCUMENT");
        return result;
    }
    private PolicyChunker() {}
}
