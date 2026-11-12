package com.intermarche.backoffice.engine.stock;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
/** Exact continuous flow with availability windows. Expiring unused goods do not forbid a promise for today. */
public final class DatedStockAvailability {
 private DatedStockAvailability(){}
 public record Batch(String key,LocalDateTime availableAt,LocalDateTime cutoff,BigDecimal quantity){}
 public record Demand(String key,LocalDateTime at,BigDecimal quantity,boolean commercial,Set<String> allowedBatches){}
 public record Extra(BigDecimal quantity,Map<String,BigDecimal> batches){}
 private static final BigDecimal ZERO=BigDecimal.ZERO;
 private static boolean eligible(Batch b,Demand d){return !b.availableAt.isAfter(d.at)&&(b.cutoff==null||d.at.isBefore(b.cutoff))&&(d.allowedBatches==null||d.allowedBatches.contains(b.key));}
 private static final class Edge {
  final int to,reverse;final BigDecimal initial;BigDecimal residual;
  Edge(int to,int reverse,BigDecimal initial,BigDecimal residual){this.to=to;this.reverse=reverse;this.initial=initial;this.residual=residual;}
  BigDecimal used(){return initial.subtract(residual);}
 }
 private static final class Graph {
  final List<List<Edge>> nodes=new ArrayList<>();
  int node(){nodes.add(new ArrayList<>());return nodes.size()-1;}
  Edge edge(int from,int to,BigDecimal capacity){Edge forward=new Edge(to,nodes.get(to).size(),capacity,capacity),reverse=new Edge(from,nodes.get(from).size(),ZERO,ZERO);nodes.get(from).add(forward);nodes.get(to).add(reverse);return forward;}
  Graph copy(){Graph copy=new Graph();for(var list:nodes){var edges=new ArrayList<Edge>();for(var e:list)edges.add(new Edge(e.to,e.reverse,e.initial,e.residual));copy.nodes.add(edges);}return copy;}
  BigDecimal augment(int source,int sink){BigDecimal total=ZERO;
   while(true){int[] parent=new int[nodes.size()],via=new int[nodes.size()];Arrays.fill(parent,-1);parent[source]=source;var queue=new ArrayDeque<Integer>();queue.add(source);
    while(!queue.isEmpty()&&parent[sink]<0){int from=queue.remove();var edges=nodes.get(from);for(int n=0;n<edges.size();n++){Edge edge=edges.get(n);if(edge.residual.signum()>0&&parent[edge.to]<0){parent[edge.to]=from;via[edge.to]=n;queue.add(edge.to);if(edge.to==sink)break;}}}
    if(parent[sink]<0)return total;BigDecimal amount=null;for(int to=sink;to!=source;to=parent[to]){Edge e=nodes.get(parent[to]).get(via[to]);amount=amount==null?e.residual:amount.min(e.residual);}
    for(int to=sink;to!=source;to=parent[to]){Edge edge=nodes.get(parent[to]).get(via[to]);edge.residual=edge.residual.subtract(amount);Edge reverse=nodes.get(to).get(edge.reverse);reverse.residual=reverse.residual.add(amount);}total=total.add(amount);
   }
  }
 }
 private static final class Network {
  final Graph graph=new Graph();final int source,sink;final List<Batch> batches;final List<Demand> demands;final int[] batchNodes;final Edge[][] assignments;final Edge[] outputs;final BigDecimal requested,supplied;
  Network(Collection<Batch> input,Collection<Demand> wanted){
   batches=input.stream().filter(b->b.quantity.signum()>0).sorted(Comparator.comparing(Batch::cutoff,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Batch::availableAt).thenComparing(Batch::key)).toList();demands=wanted.stream().filter(d->d.quantity.signum()>0).sorted(Comparator.comparing(Demand::at).thenComparing(Demand::key)).toList();source=graph.node();batchNodes=new int[batches.size()];for(int b=0;b<batches.size();b++){batchNodes[b]=graph.node();graph.edge(source,batchNodes[b],batches.get(b).quantity);}int[] targets=new int[demands.size()];for(int d=0;d<targets.length;d++)targets[d]=graph.node();sink=graph.node();assignments=new Edge[batches.size()][demands.size()];outputs=new Edge[demands.size()];
   for(int d=0;d<demands.size();d++){Demand demand=demands.get(d);outputs[d]=graph.edge(targets[d],sink,demand.quantity);for(int b=0;b<batches.size();b++)if(eligible(batches.get(b),demand))assignments[b][d]=graph.edge(batchNodes[b],targets[d],batches.get(b).quantity);}
   requested=demands.stream().map(Demand::quantity).reduce(ZERO,BigDecimal::add);supplied=graph.augment(source,sink);
  }
  Extra extra(LocalDateTime at,BigDecimal margin){
   if(supplied.compareTo(requested)<0)return new Extra(ZERO,Map.of());Graph copy=graph.copy();
   if(margin.signum()>0){int reserve=copy.node();copy.edge(reserve,sink,margin);Demand reserveDemand=new Demand("margin",at,margin,false,null);for(int b=0;b<batches.size();b++)if(eligible(batches.get(b),reserveDemand))copy.edge(batchNodes[b],reserve,batches.get(b).quantity);if(copy.augment(source,sink).compareTo(margin)<0)return new Extra(ZERO,Map.of());}
   int extra=copy.node();BigDecimal capacity=batches.stream().map(Batch::quantity).reduce(ZERO,BigDecimal::add);copy.edge(extra,sink,capacity);var edges=new LinkedHashMap<String,Edge>();Demand added=new Demand("extra",at,capacity,true,null);for(int b=0;b<batches.size();b++)if(eligible(batches.get(b),added))edges.put(batches.get(b).key,copy.edge(batchNodes[b],extra,batches.get(b).quantity));BigDecimal amount=copy.augment(source,sink);var selected=new LinkedHashMap<String,BigDecimal>();for(var e:edges.entrySet())if(e.getValue().used().signum()>0)selected.put(e.getKey(),e.getValue().used());return new Extra(amount,selected);
  }
  StockAvailability.Period period(LocalDateTime at,BigDecimal margin){
   BigDecimal remaining=ZERO,commercial=ZERO,servedCommercial=ZERO,unmetPhysical=ZERO;
   for(int b=0;b<batches.size();b++){Batch batch=batches.get(b);if(batch.availableAt.isAfter(at)||batch.cutoff!=null&&!at.isBefore(batch.cutoff))continue;BigDecimal consumed=ZERO;for(int d=0;d<demands.size();d++)if(!demands.get(d).at.isAfter(at)&&assignments[b][d]!=null)consumed=consumed.add(assignments[b][d].used());remaining=remaining.add(batch.quantity.subtract(consumed));}
   for(int d=0;d<demands.size();d++)if(!demands.get(d).at.isAfter(at)){Demand demand=demands.get(d);if(demand.commercial){commercial=commercial.add(demand.quantity);servedCommercial=servedCommercial.add(outputs[d].used());}else unmetPhysical=unmetPhysical.add(demand.quantity.subtract(outputs[d].used()));}
   var p=new StockAvailability.Period();p.from=at;p.committed=commercial;p.physicalAndExpected=remaining.add(servedCommercial).subtract(unmetPhysical);p.balance=p.physicalAndExpected.subtract(commercial).subtract(margin);p.availableToPromise=extra(at,margin).quantity;return p;
  }
 }
 public static Extra maximumExtra(Collection<Batch> batches,Collection<Demand> demands,LocalDateTime at,BigDecimal margin){return new Network(batches,demands).extra(at,margin);}
 public static StockAvailability.Timeline calculate(LocalDateTime asOf,BigDecimal physical,BigDecimal margin,Collection<Batch> batches,Collection<Demand> demands){
  if(margin.signum()<0)throw new IllegalArgumentException("Negative safety margin");Network network=new Network(batches,demands);var times=new TreeSet<LocalDateTime>();times.add(asOf);for(var b:batches){if(b.availableAt.isAfter(asOf))times.add(b.availableAt);if(b.cutoff!=null&&b.cutoff.isAfter(asOf))times.add(b.cutoff);}for(var d:demands)if(d.at.isAfter(asOf))times.add(d.at);
  var periods=new ArrayList<StockAvailability.Period>();for(var at:times)periods.add(network.period(at,margin));for(int n=0;n+1<periods.size();n++)periods.get(n).until=periods.get(n+1).from;
  var result=new StockAvailability.Timeline();result.asOf=asOf;result.physical=physical;result.safetyMargin=margin;result.periods=periods;for(var p:periods)if(p.availableToPromise.signum()>0){result.nextAvailableAt=p.from;break;}
  if(periods.getFirst().availableToPromise.signum()==0){for(var demand:demands.stream().filter(Demand::commercial).sorted(Comparator.comparing(Demand::at)).toList()){var p=periods.stream().filter(x->!x.from.isAfter(demand.at)).reduce((a,b)->b).orElse(periods.getFirst());if(p.balance.signum()<=0){result.constrainedAt=demand.at;break;}}}
  BigDecimal useful=ZERO;if(result.constrainedAt!=null){for(var b:batches)if(!b.availableAt.isAfter(asOf)&&(b.cutoff==null||result.constrainedAt.isBefore(b.cutoff)))useful=useful.add(b.quantity);}
  BigDecimal committed=demands.stream().filter(Demand::commercial).map(Demand::quantity).reduce(ZERO,BigDecimal::add);result.protectionRequired=result.constrainedAt!=null&&useful.compareTo(margin)>0;result.quantityToProtect=result.protectionRequired?useful.subtract(margin).min(committed):ZERO;return result;
 }
}
