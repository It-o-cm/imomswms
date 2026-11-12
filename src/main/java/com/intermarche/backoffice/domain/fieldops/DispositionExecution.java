package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import jakarta.persistence.*;
@Entity @DiscriminatorValue("CANCELLATION_DISPOSITION")
public class DispositionExecution extends HandlingExecution {
 @OneToMany(cascade=CascadeType.ALL) @JoinColumn(name="disposition_execution_id") public java.util.Set<com.intermarche.backoffice.domain.executing.LoadedTote> loadedTotes=new java.util.HashSet<>();
}
