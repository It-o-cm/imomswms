-- Operational line instructions never replace quantities or physical movement receipts.
create sequence line_directives_SEQ start with 1 increment by 50;
create sequence line_directive_changes_SEQ start with 1 increment by 50;
create sequence line_instruction_acks_SEQ start with 1 increment by 50;
create table line_directives(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),handlingOrder_id bigint not null references handling_orders(id),lineKind varchar(30) not null,lineId bigint not null,state varchar(20) not null,priority integer not null,instruction varchar(2000) not null,instructionVersion bigint not null,unique(handlingOrder_id,lineKind,lineId));
create table line_directive_changes(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,directive_id bigint not null references line_directives(id),actor varchar(100) not null,requestKey varchar(64) not null,fingerprint varchar(64) not null,result text not null,unique(directive_id,actor,requestKey));
create table line_instruction_acks(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,directive_id bigint not null references line_directives(id),actor varchar(100) not null,instructionVersion bigint not null,unique(directive_id,actor,instructionVersion));
create index idx_line_directives_store_order on line_directives(store_id,handlingOrder_id);
create sequence mission_handovers_SEQ start with 1 increment by 50;
create table mission_handovers(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),handlingOrder_id bigint not null references handling_orders(id),source_id bigint not null references employees(id),target_id bigint not null references employees(id),requester varchar(100) not null,requestKey varchar(64) not null,fingerprint varchar(64) not null,orderRevision varchar(64) not null,reason varchar(500) not null,state varchar(20) not null,expiresAt timestamp(6) not null,readyAt timestamp(6) not null,result text,unique(store_id,requester,requestKey));
create index idx_handover_store_state on mission_handovers(store_id,state,id);
create sequence support_types_SEQ start with 1 increment by 50;
create sequence physical_supports_SEQ start with 1 increment by 50;
create table support_types(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),code varchar(30) not null,label varchar(100) not null,kind varchar(20) not null,maxWeightKg numeric(12,3),maxVolumeLiters numeric(12,3),allowedZones varchar(80) not null,unique(store_id,code));
create table physical_supports(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),type_id bigint not null references support_types(id),tote_id bigint not null unique references totes(id),code varchar(20) not null,state varchar(20) not null,unique(store_id,code));
-- Keep historic tote identity and individual volume. No content or location is moved.
insert into support_types(id,version,checksum,created_at,updated_at,store_id,code,label,kind,allowedZones) select next value for support_types_SEQ,0,0,current_timestamp,current_timestamp,id,'TOTE_STANDARD','Bac standard','TOTE','AMBIENT,HOUSEHOLD' from stores;
insert into support_types(id,version,checksum,created_at,updated_at,store_id,code,label,kind,allowedZones) select next value for support_types_SEQ,0,0,current_timestamp,current_timestamp,id,'TOTE_INSULATED','Bac isotherme','TOTE','CHILLED,FROZEN' from stores;
insert into physical_supports(id,version,checksum,created_at,updated_at,store_id,type_id,tote_id,code,state) select next value for physical_supports_SEQ,0,0,current_timestamp,current_timestamp,t.store_id,s.id,t.id,t.code,'AVAILABLE' from totes t join support_types s on s.store_id=t.store_id and s.code=case when t.tote_type='INSULATED' then 'TOTE_INSULATED' else 'TOTE_STANDARD' end;
alter table handling_orders add column disposition_origin_order_id bigint references handling_orders(id);
alter table handling_orders add column disposition_customer_order_id bigint references orders(id);
alter table handling_orders add column disposition_building_id bigint references buildings(id);
create sequence cancellation_dispositions_SEQ start with 1 increment by 50;
create sequence cancelled_mission_lines_SEQ start with 1 increment by 50;
create table cancellation_dispositions(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,handlingOrder_id bigint not null references handling_orders(id),item_id bigint not null unique references picked_items(id),source_id bigint not null references loaded_totes(id),quantity numeric(19,3) not null,state varchar(20) not null,policy varchar(20) not null,instruction varchar(500) not null,destination_id bigint references item_locations(id),destinationTote_id bigint references loaded_totes(id),targetItemId bigint,confirmationKey varchar(64),fingerprint varchar(64),result text);
create table cancelled_mission_lines(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),line_id bigint not null unique references lot_lines(id),reason varchar(500) not null,actor varchar(100) not null);
create index idx_disposition_order_state on cancellation_dispositions(handlingOrder_id,state);
create sequence customer_cancellations_SEQ start with 1 increment by 50;
create table customer_cancellations(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),customerOrderId bigint,requestKey varchar(64) not null,fingerprint varchar(64) not null,actor varchar(100) not null,result text not null,unique(store_id,requestKey));
alter table cancellation_dispositions add column hold_id bigint references stock_commitments(id);

alter table loaded_totes add column disposition_execution_id bigint references handling_executions(id);
-- Global technical sequence: handling order codes are globally unique, although stores allocate independently.
create sequence cancellation_order_codes_SEQ start with 1 increment by 1;
