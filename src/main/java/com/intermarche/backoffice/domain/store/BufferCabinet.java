package com.intermarche.backoffice.domain.store;
import com.intermarche.backoffice.domain.BaseEntity;
import jakarta.persistence.*;
@Entity @Table(name="buffer_cabinets",uniqueConstraints=@UniqueConstraint(columnNames={"buffer_id","code"}))
public class BufferCabinet extends BaseEntity {
 @ManyToOne(optional=false) public Buffer buffer;
 @Column(nullable=false,length=80) public String code;
 @Column(name="cell_columns",nullable=false) public int columns;
 @Column(name="cell_rows",nullable=false) public int rows;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public TemperatureZone zone;
 @Override public int getChecksum(){return java.util.Objects.hash(code,columns,rows,zone);}
}
