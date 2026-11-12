package com.intermarche.backoffice.domain.executing;
import com.intermarche.backoffice.domain.handling.HandlingExecution;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
@Entity
@DiscriminatorValue("REPLENISHMENT")
public class ReplenishmentExecution extends HandlingExecution { public String phase; }
