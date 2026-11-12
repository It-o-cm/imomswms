package com.intermarche.backoffice.engine.semantic;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline E5 inference: symmetric query prefix, masked mean pooling, L2 normalization. */
@ApplicationScoped
public class LocalEmbeddingModel implements AutoCloseable {
    @ConfigProperty(name="imomswms.embeddings.model-directory",defaultValue="models/multilingual-e5-small") String directory;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private String version;
    public synchronized String version() { initialize();return version; }
    private void initialize() {
        if(session!=null)return;
        Path path=Path.of(directory);
        if(!Files.isRegularFile(path.resolve("model.onnx")) || !Files.isRegularFile(path.resolve("tokenizer.json")))
            throw new IllegalStateException("Modèle local absent. Exécutez python3 scripts/install-embedding-model.py sur le serveur.");
        System.setProperty("ai.djl.offline","true");System.setProperty("OPT_OUT_TRACKING","true");
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String file:List.of("model.onnx","tokenizer.json"))try(var input=Files.newInputStream(path.resolve(file))){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
            version="e5-small/query-mean-l2-512/v1/"+HexFormat.of().formatHex(digest.digest());
            tokenizer=HuggingFaceTokenizer.newInstance(path.resolve("tokenizer.json"),Map.of("maxLength","512","modelMaxLength","512","truncation","longest_first","padding","do_not_pad"));
            try(var options=new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
                session=OrtEnvironment.getEnvironment().createSession(path.resolve("model.onnx").toString(),options);
            }
        } catch(Exception|LinkageError e){close();throw new IllegalStateException("Le modèle d’embeddings local ne peut pas être chargé : "+e.getMessage(),e);}
    }
    public synchronized float[] embed(String text) {
        initialize();
        if(text==null || text.isBlank())throw new IllegalArgumentException("Caractéristiques du produit manquantes.");
        var encoded=tokenizer.encode("query: "+text);
        var environment=OrtEnvironment.getEnvironment();
        try(var ids=OnnxTensor.createTensor(environment,new long[][]{encoded.getIds()});
            var mask=OnnxTensor.createTensor(environment,new long[][]{encoded.getAttentionMask()});
            var types=OnnxTensor.createTensor(environment,new long[][]{encoded.getTypeIds()})) {
            var inputs=new HashMap<String,OnnxTensor>();inputs.put("input_ids",ids);inputs.put("attention_mask",mask);
            if(session.getInputNames().contains("token_type_ids"))inputs.put("token_type_ids",types);
            try(var result=session.run(inputs)) {
                Object output=result.get(0).getValue();
                if(!(output instanceof float[][][] tokens))throw new IllegalStateException("Sortie ONNX inattendue.");
                return pool(tokens[0],encoded.getAttentionMask());
            }
        }catch(OrtException e){throw new IllegalStateException("Échec du calcul local de l’embedding.",e);}
    }
    static float[] pool(float[][] tokens,long[] mask) {
        float[] vector=new float[tokens[0].length];int count=0;
        for(int i=0;i<tokens.length;i++)if(mask[i]!=0){for(int j=0;j<vector.length;j++)vector[j]+=tokens[i][j];count++;}
        double norm=0;for(int j=0;j<vector.length;j++){vector[j]/=Math.max(1,count);norm+=(double)vector[j]*vector[j];}
        if(!Double.isFinite(norm)||norm==0)throw new IllegalStateException("Vecteur sémantique invalide.");
        for(int j=0;j<vector.length;j++)vector[j]/=(float)Math.sqrt(norm);
        return vector;
    }
    public static double distance(float[] a,float[] b) {
        if(a.length!=b.length)throw new IllegalArgumentException("Dimensions d’embeddings incompatibles.");
        double dot=0;for(int i=0;i<a.length;i++)dot+=(double)a[i]*b[i];
        return Math.max(0,Math.min(2,1-dot));
    }
    @PreDestroy public synchronized void close(){if(session!=null){try{session.close();}catch(OrtException ignored){}session=null;}if(tokenizer!=null){tokenizer.close();tokenizer=null;}}
}
