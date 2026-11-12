package com.intermarche.backoffice.engine.catalog;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.intermarche.backoffice.domain.util.DateTimeProvider;

/** D02 / subset v1: strict GS1 element strings; never mutates a product or stock. */
public final class Gs1Parser {
 public record Parsed(String identifier,Map<String,String> fields,BigDecimal quantity,String unit,List<String> warnings){}
 private static final Set<String> FIXED=Set.of("01","02","11","13","15","17");
 private static final Set<String> VARIABLE=Set.of("10","21","30","37");
 public static boolean gtinValid(String s){if(s==null || !s.matches("(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})"))return false;int sum=0,weight=3;for(int i=s.length()-2;i>=0;i--){sum+=(s.charAt(i)-'0')*weight;weight=4-weight;}return (10-sum%10)%10==s.charAt(s.length()-1)-'0';}
 public static String gtin(String value){if(!gtinValid(value))throw new IllegalArgumentException("Clé de contrôle GTIN/EAN/UPC invalide");return "0".repeat(14-value.length())+value;}
 private static int length(String ai){return ai.equals("01")||ai.equals("02")?14:6;}
 private static boolean measure(String ai){return ai.matches("(?:310|315)[0-5]");}
 public static Parsed parse(String raw){
  if(raw==null || raw.isBlank() || raw.length()>512)throw new IllegalArgumentException("Code vide ou trop long");
  String code=raw.trim();if(code.startsWith("]E0")){String value=code.substring(3);if(!value.matches("(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})"))throw new IllegalArgumentException("EAN/UPC scanner invalide");return new Parsed(value,Map.of(),null,null,List.of());}if(code.startsWith("]")){if(!Set.of("]C1","]d2","]Q3","]e0").contains(code.substring(0,Math.min(3,code.length()))))throw new IllegalArgumentException("Symbologie non prise en charge");code=code.substring(3);}
  if(!code.startsWith("(") && !raw.startsWith("]") && code.indexOf(29)<0 && !(code.length()>14&&(code.startsWith("01")||code.startsWith("02"))))return new Parsed(code,Map.of(),null,null,List.of());
  var fields=new LinkedHashMap<String,String>();boolean hri=code.startsWith("(");int pos=0;
  while(pos<code.length()){
   if(code.charAt(pos)==29){pos++;continue;}
   String ai,value;
   if(hri){
    if(code.charAt(pos)!='(')throw new IllegalArgumentException("Délimiteur GS1 attendu");int end=code.indexOf(')',pos);if(end<0)throw new IllegalArgumentException("AI incomplet");ai=code.substring(pos+1,end);int next=code.indexOf('(',end+1);value=code.substring(end+1,next<0?code.length():next);pos=next<0?code.length():next;
   }else{
    if(pos+2>code.length())throw new IllegalArgumentException("AI incomplet");ai=code.substring(pos,pos+2);
    if(ai.equals("31")){if(pos+4>code.length())throw new IllegalArgumentException("AI de mesure incomplet");ai=code.substring(pos,pos+4);}
    pos+=ai.length();if(FIXED.contains(ai)||measure(ai)){int n=length(ai);if(pos+n>code.length())throw new IllegalArgumentException("Valeur GS1 incomplète");value=code.substring(pos,pos+n);pos+=n;}
    else{int end=code.indexOf(29,pos);value=code.substring(pos,end<0?code.length():end);pos=end<0?code.length():end;}
   }
   if(!FIXED.contains(ai)&&!VARIABLE.contains(ai)&&!measure(ai))throw new IllegalArgumentException("AI non pris en charge : "+ai);
   if(fields.putIfAbsent(ai,value)!=null)throw new IllegalArgumentException("AI dupliqué : "+ai);
   if(value.isEmpty() || value.chars().anyMatch(c->c<32 || c>126) || value.indexOf(')')>=0)throw new IllegalArgumentException("Valeur GS1 invalide");
   if(FIXED.contains(ai)||measure(ai)){if(value.length()!=length(ai)||!value.matches("[0-9]+"))throw new IllegalArgumentException("Longueur ou contenu numérique GS1 invalide");}
   else if(Set.of("30","37").contains(ai)){if(!value.matches("[0-9]{1,8}")||new BigDecimal(value).signum()<=0)throw new IllegalArgumentException("Nombre d’unités GS1 invalide");}
   else if(value.length()>20)throw new IllegalArgumentException("Lot ou série de plus de 20 caractères");
  }
  if(fields.containsKey("01")==fields.containsKey("02"))throw new IllegalArgumentException("Un seul GTIN (01 ou 02) est requis");
  if(fields.containsKey("02")&&!fields.containsKey("37"))throw new IllegalArgumentException("L’AI 02 requiert le nombre de contenus (37)");
  if(fields.containsKey("37")&&!fields.containsKey("02"))throw new IllegalArgumentException("L’AI 37 requiert le GTIN du contenu (02)");
  var warnings=new ArrayList<String>();for(String ai:List.of("11","13","15","17"))if(fields.containsKey(ai))fields.put(ai,date(fields.get(ai),warnings).toString());
  BigDecimal quantity=null;String unit=null;
  for(var field:fields.entrySet())if(measure(field.getKey())||Set.of("30","37").contains(field.getKey())){
   if(quantity!=null)throw new IllegalArgumentException("Plusieurs quantités GS1 incompatibles : arbitrage requis");
   quantity=new BigDecimal(field.getValue());unit="PCS";
   if(measure(field.getKey())){quantity=quantity.movePointLeft(field.getKey().charAt(3)-'0');unit=field.getKey().startsWith("310")?"KG":"L";}
   if(quantity.signum()<=0)throw new IllegalArgumentException("Mesure GS1 nulle");
  }
  return new Parsed(gtin(fields.getOrDefault("01",fields.get("02"))),Collections.unmodifiableMap(fields),quantity,unit,List.copyOf(warnings));
 }
 private static LocalDate date(String value,List<String> warnings){
  int now=DateTimeProvider.now().getYear(),year=now/100*100+Integer.parseInt(value.substring(0,2));if(year>now+49)year-=100;if(year<now-50)year+=100;
  int month=Integer.parseInt(value.substring(2,4)),day=Integer.parseInt(value.substring(4));
  try{if(day==0){warnings.add("DATE_DAY_UNSPECIFIED");return YearMonth.of(year,month).atEndOfMonth();}return LocalDate.of(year,month,day);}catch(DateTimeException e){throw new IllegalArgumentException("Date GS1 invalide");}
 }
 private Gs1Parser(){}
}
