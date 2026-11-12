package com.intermarche.backoffice.engine.semantic;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.persistence.*;
import java.util.Objects;
@Entity @Table(name="product_embeddings",uniqueConstraints=@UniqueConstraint(columnNames="product_id"))
public class ProductEmbedding extends BaseEntity {
 @OneToOne(optional=false) public Product product;
 @Column(nullable=false,length=120) public String modelVersion;
 @Column(nullable=false,length=64) public String textHash;
 @Lob @Column(nullable=false) public byte[] vector;
 @Override public int getChecksum(){return Objects.hash(modelVersion,textHash);}
}
