package com.intermarche.backoffice.engine.semantic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
class LocalEmbeddingModelTest {
 @Test void maskedMeanPoolingNormalizesAndIgnoresPadding(){assertArrayEquals(new float[]{0.6f,0.8f},LocalEmbeddingModel.pool(new float[][]{{3,4},{999,999}},new long[]{1,0}),0.0001f);}
 @Test void distanceIsZeroForIdenticalAndTwoForOppositeVectors(){assertEquals(0,LocalEmbeddingModel.distance(new float[]{1,0},new float[]{1,0}),0.0001);assertEquals(2,LocalEmbeddingModel.distance(new float[]{1,0},new float[]{-1,0}),0.0001);assertThrows(IllegalArgumentException.class,()->LocalEmbeddingModel.distance(new float[]{1},new float[]{1,0}));}
 @Test @EnabledIfSystemProperty(named="imomswms.embeddings.integration",matches="true")
 void realOfflineE5RanksMilkCloserThanDetergentAndIsDeterministic(){
  try(var model=new LocalEmbeddingModel()){model.directory="models/multilingual-e5-small";
   float[] a=model.embed("Lait de vache demi-écrémé, bouteille de 1 litre, pour le petit-déjeuner.");
   float[] b=model.embed("Bouteille d'un litre de lait demi écrémé de vache, pour boire au petit déjeuner.");
   float[] c=model.embed("Lessive liquide pour nettoyer les vêtements et le linge en machine.");
   assertEquals(384,a.length);assertTrue(LocalEmbeddingModel.distance(a,b)<LocalEmbeddingModel.distance(a,c));assertArrayEquals(a,model.embed("Lait de vache demi-écrémé, bouteille de 1 litre, pour le petit-déjeuner."),0.00001f);System.out.println("REAL_E5 distances: milk="+LocalEmbeddingModel.distance(a,b)+", detergent="+LocalEmbeddingModel.distance(a,c));
  }
 }
}
