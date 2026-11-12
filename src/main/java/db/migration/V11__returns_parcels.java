package db.migration;
import org.flywaydb.core.api.migration.*;import java.util.ArrayList;
/** Additive returns and parcel workflow, preserving all existing balances and shipments. */
public class V11__returns_parcels extends BaseJavaMigration {
@Override public void migrate(Context context)throws Exception{
 var connection=context.getConnection();var checks=new ArrayList<String>();
 try(var statement=connection.createStatement();var rows=statement.executeQuery("select tc.constraint_name,cc.check_clause from information_schema.table_constraints tc join information_schema.check_constraints cc on tc.constraint_catalog=cc.constraint_catalog and tc.constraint_schema=cc.constraint_schema and tc.constraint_name=cc.constraint_name where tc.table_schema='PUBLIC' and tc.table_name='ITEM_LOCATIONS' and tc.constraint_type='CHECK'")){while(rows.next())if(rows.getString(2).contains("'PENDING_SUPPLY'")&&rows.getString(2).contains("'SHELF'"))checks.add(rows.getString(1));}
 try(var s=connection.createStatement()){
 for(String check:checks)s.execute("alter table item_locations drop constraint \""+check.replace("\"","\"\"")+"\"");
 s.execute("alter table item_locations add constraint chk_location_logistics_type check(type in ('BIN','PENDING_SUPPLY','PICKING_TOTE','SHELF','BUFFER_TOTE_STOCK','RETURN_TRANSIT'))");
s.execute("create sequence product_returns_SEQ start with 1 increment by 50");
s.execute("create table product_returns(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),shipment_id bigint references shipments(id),code varchar(255),reference varchar(255),reportedBy varchar(255),reason varchar(2000),state varchar(255),revisionNumber bigint not null,receivedAt timestamp(6))");
s.execute("create sequence product_return_lines_SEQ start with 1 increment by 50");
s.execute("create table product_return_lines(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,productReturn_id bigint not null references product_returns(id),product_id bigint not null references products(id),segment_id bigint not null references stock_segments(id),shipmentLine_id bigint references shipment_lines(id),quantity numeric(19,6) not null,inspection varchar(255),inspectedBy varchar(255),inspectionReason varchar(2000),inspectedAt timestamp(6))");
s.execute("create sequence return_dispositions_SEQ start with 1 increment by 50");
s.execute("create table return_dispositions(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,line_id bigint not null references product_return_lines(id),destination_id bigint references item_locations(id),transitSegment_id bigint references stock_segments(id),quantity numeric(19,6) not null,decision varchar(255),state varchar(255),decidedBy varchar(255),performedBy varchar(255),reason varchar(2000),pickedAt timestamp(6),completedAt timestamp(6),revisionNumber bigint not null)");
s.execute("create sequence parcels_SEQ start with 1 increment by 50");
s.execute("create table parcels(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),order_id bigint not null references orders(id),code varchar(255) not null unique,state varchar(255),packagingType varchar(255),createdBy varchar(255),closedBy varchar(255),weightKg numeric(19,6),lengthCm numeric(19,6),widthCm numeric(19,6),heightCm numeric(19,6),closedAt timestamp(6),revisionNumber bigint not null,closedSnapshot clob)");
s.execute("create sequence parcel_contents_SEQ start with 1 increment by 50");
s.execute("create table parcel_contents(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,parcel_id bigint not null references parcels(id),pickedItem_id bigint not null references picked_items(id),quantity numeric(19,6) not null,traceSnapshot clob,sourceToteId bigint,sourceProductId bigint,sourceQuantity numeric(19,6))");
s.execute("create sequence parcel_control_policies_SEQ start with 1 increment by 50");
s.execute("create table parcel_control_policies(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id) unique,requireWeight boolean not null,requireDimensions boolean not null,requireContentConfirmation boolean not null,requireScan boolean not null,revisionNumber integer not null,updatedBy varchar(255))");
s.execute("create sequence logistics_history_SEQ start with 1 increment by 50");
s.execute("create table logistics_history(id bigint primary key,version bigint,checksum integer,created_at timestamp(6) not null,updated_at timestamp(6) not null,store_id bigint not null references stores(id),subjectType varchar(255),action varchar(255),actor varchar(255),subjectId bigint,reason varchar(2000),snapshot clob)");
s.execute("create index idx_returns_store_state on product_returns(store_id,state,id)");
s.execute("create index idx_return_lines_case on product_return_lines(productReturn_id)");
s.execute("create index idx_return_disposition_line on return_dispositions(line_id,state)");
s.execute("create index idx_parcel_store_order on parcels(store_id,order_id,state)");
s.execute("create index idx_parcel_content_item on parcel_contents(pickedItem_id,parcel_id)");
s.execute("create index idx_logistics_subject on logistics_history(store_id,subjectType,subjectId,id)");
}}
}
