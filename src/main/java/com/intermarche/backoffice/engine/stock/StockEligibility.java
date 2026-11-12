package com.intermarche.backoffice.engine.stock;
import java.time.*;
import java.util.*;
/** Pure date/quality rules, shared by allocation and availability projections. */
public final class StockEligibility {
 private StockEligibility(){}
 public static boolean qualityAllowed(String quality){return Set.of("UNASSESSED","AVAILABLE").contains(quality);}
 public static LocalDateTime cutoff(LocalDate useBy,LocalDate bestBefore,int minimumDays,boolean bestBeforeStrict){
  LocalDate limit=useBy;if(bestBefore!=null&&(bestBeforeStrict||minimumDays>0))limit=limit==null?bestBefore:limit.isBefore(bestBefore)?limit:bestBefore;
  return limit==null?null:limit.plusDays(1).minusDays(minimumDays).atStartOfDay();
 }
 public static boolean dateAllowed(LocalDateTime at,LocalDate useBy,LocalDate bestBefore,int minimumDays,boolean bestBeforeStrict){LocalDateTime cutoff=cutoff(useBy,bestBefore,minimumDays,bestBeforeStrict);return cutoff==null||at.isBefore(cutoff);}
 public record Candidate(Long id,LocalDateTime firstReceived,LocalDate useBy,LocalDate bestBefore){}
 public static Comparator<Candidate> comparator(String method){Comparator<Candidate> fifo=Comparator.comparing(Candidate::firstReceived,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Candidate::id);return "FIFO".equals(method)?fifo:Comparator.comparing((Candidate c)->c.useBy!=null?c.useBy:c.bestBefore,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(fifo);}
}
