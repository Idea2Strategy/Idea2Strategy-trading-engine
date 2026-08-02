package com.idea2strategy.trading.persistence.position;

import com.idea2strategy.trading.application.port.PositionLotStore;
import com.idea2strategy.trading.application.position.CloseLongPositionCommand;
import com.idea2strategy.trading.application.position.OpenPositionLotCommand;
import com.idea2strategy.trading.application.position.PositionConflictException;
import com.idea2strategy.trading.application.position.PositionMutationResult;
import com.idea2strategy.trading.domain.position.LotClose;
import com.idea2strategy.trading.domain.position.PositionLot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresPositionLotStore implements PositionLotStore {
    private static final int SCALE=18;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    public PostgresPositionLotStore(JdbcClient jdbc, PlatformTransactionManager manager){
        this.jdbc=Objects.requireNonNull(jdbc,"jdbc");this.transaction=new TransactionTemplate(Objects.requireNonNull(manager,"manager"));
    }
    @Override public PositionMutationResult open(OpenPositionLotCommand command){
        Objects.requireNonNull(command,"command");return transaction.execute(status->openTx(command));
    }
    @Override public PositionMutationResult closeLong(CloseLongPositionCommand command){
        Objects.requireNonNull(command,"command");return transaction.execute(status->closeTx(command));
    }

    private PositionMutationResult openTx(OpenPositionLotCommand c){
        String fp=fingerprint("OPEN",c.botId(),c.partitionId(),c.flowId(),c.instrumentId(),c.fillRecordId(),c.quantity(),c.price(),c.commission(),c.occurredAt());
        Optional<Receipt> prior=receipt(c.fillRecordId()); if(prior.isPresent())return replay(prior.orElseThrow(),fp);
        PositionLot lot=PositionLot.open(c.botId(),c.partitionId(),c.flowId(),c.instrumentId(),c.fillRecordId(),c.quantity(),c.price(),c.commission(),c.occurredAt());
        int inserted=jdbc.sql("""
            insert into trading.execution_position_lot(lot_id,bot_id,partition_id,flow_id,instrument_id,
                opening_fill_record_id,opened_quantity,unit_price,opening_commission,opened_cost_basis,opened_at)
            values(:lotId,:botId,:partitionId,:flowId,:instrumentId,:fillId,:quantity,:price,:commission,:basis,:at)
            on conflict do nothing
            """).param("lotId",lot.lotId()).param("botId",c.botId()).param("partitionId",c.partitionId())
            .param("flowId",c.flowId()).param("instrumentId",c.instrumentId()).param("fillId",c.fillRecordId())
            .param("quantity",lot.openedQuantity()).param("price",lot.unitPrice()).param("commission",lot.openingCommission())
            .param("basis",lot.openedCostBasis()).param("at",offset(c.occurredAt())).update();
        if(inserted==0){Receipt raced=receipt(c.fillRecordId()).orElseThrow(()->conflict("opening fill identity conflict"));return replay(raced,fp);}
        jdbc.sql("insert into trading.execution_position_lot_projection values(:lotId,:q,:basis,1,null,:at)")
            .param("lotId",lot.lotId()).param("q",lot.remainingQuantity()).param("basis",lot.remainingCostBasis()).param("at",offset(c.occurredAt())).update();
        insertMovement(c.fillRecordId(),lot.lotId(),"OPEN",lot.openedQuantity(),lot.openedCostBasis(),BigDecimal.ZERO,lot.remainingQuantity(),lot.remainingCostBasis(),c.occurredAt());
        jdbc.sql("""
            insert into trading.execution_flow_position_projection(bot_id,partition_id,flow_id,instrument_id,quantity,cost_basis,realized_pnl,version,updated_at)
            values(:botId,:partitionId,:flowId,:instrumentId,:q,:basis,0,1,:at)
            on conflict(bot_id,partition_id,flow_id,instrument_id) do update set
                quantity=trading.execution_flow_position_projection.quantity+excluded.quantity,
                cost_basis=trading.execution_flow_position_projection.cost_basis+excluded.cost_basis,
                version=trading.execution_flow_position_projection.version+1,updated_at=excluded.updated_at
            """).param("botId",c.botId()).param("partitionId",c.partitionId()).param("flowId",c.flowId())
            .param("instrumentId",c.instrumentId()).param("q",lot.openedQuantity()).param("basis",lot.openedCostBasis())
            .param("at",offset(c.occurredAt())).update();
        FlowProjection ending=flow(c.botId(),c.partitionId(),c.flowId(),c.instrumentId(),false).orElseThrow();
        PositionMutationResult result=new PositionMutationResult(c.fillRecordId(),lot.openedQuantity(),lot.openedCostBasis(),BigDecimal.ZERO,ending.quantity,ending.costBasis,1);
        insertReceipt(fp,"OPEN",result);return result;
    }

    private PositionMutationResult closeTx(CloseLongPositionCommand c){
        String fp=fingerprint("CLOSE",c.botId(),c.partitionId(),c.flowId(),c.instrumentId(),c.fillRecordId(),c.quantity(),c.price(),c.commission(),c.occurredAt());
        Optional<Receipt> prior=receipt(c.fillRecordId());if(prior.isPresent())return replay(prior.orElseThrow(),fp);
        FlowProjection current=flow(c.botId(),c.partitionId(),c.flowId(),c.instrumentId(),true).orElseThrow(()->conflict("long position does not exist"));
        if(c.quantity().compareTo(current.quantity)>0)throw conflict("close quantity exceeds long position");
        List<PositionLot> lots=availableLots(c);
        BigDecimal remaining=c.quantity();BigDecimal feeRemaining=c.commission();BigDecimal basisReleased=BigDecimal.ZERO;BigDecimal realized=BigDecimal.ZERO;int affected=0;
        for(PositionLot lot:lots){
            if(remaining.signum()==0)break;
            BigDecimal take=remaining.min(lot.remainingQuantity());
            BigDecimal allocated=take.compareTo(remaining)==0?feeRemaining:c.commission().multiply(take).divide(c.quantity(),SCALE,RoundingMode.HALF_EVEN);
            LotClose close=lot.close(c.fillRecordId(),take,c.price(),allocated,c.occurredAt());
            int updated=jdbc.sql("""
                update trading.execution_position_lot_projection set remaining_quantity=:q,remaining_cost_basis=:basis,
                    version=:nextVersion,closed_at=:closedAt,updated_at=:at where lot_id=:lotId and version=:version
                """).param("q",close.remainingLot().remainingQuantity()).param("basis",close.remainingLot().remainingCostBasis())
                .param("nextVersion",close.remainingLot().version()).param("closedAt",close.remainingLot().closedAt().map(PostgresPositionLotStore::offset).orElse(null))
                .param("at",offset(c.occurredAt())).param("lotId",lot.lotId()).param("version",lot.version()).update();
            if(updated!=1)throw conflict("lot changed concurrently");
            insertMovement(c.fillRecordId(),lot.lotId(),"CLOSE",take.negate(),close.costBasisReleased().negate(),close.realizedPnl(),close.remainingLot().remainingQuantity(),close.remainingLot().remainingCostBasis(),c.occurredAt());
            remaining=remaining.subtract(take);feeRemaining=feeRemaining.subtract(allocated);basisReleased=basisReleased.add(close.costBasisReleased());realized=realized.add(close.realizedPnl());affected++;
        }
        if(remaining.signum()!=0)throw conflict("FIFO lots do not cover position projection");
        int updated=jdbc.sql("""
            update trading.execution_flow_position_projection set quantity=quantity-:q,cost_basis=cost_basis-:basis,
                realized_pnl=realized_pnl+:pnl,version=version+1,updated_at=:at
            where bot_id=:botId and partition_id=:partitionId and flow_id=:flowId and instrument_id=:instrumentId and version=:version
            """).param("q",c.quantity()).param("basis",basisReleased).param("pnl",realized).param("at",offset(c.occurredAt()))
            .param("botId",c.botId()).param("partitionId",c.partitionId()).param("flowId",c.flowId()).param("instrumentId",c.instrumentId()).param("version",current.version).update();
        if(updated!=1)throw conflict("position changed concurrently");
        FlowProjection ending=flow(c.botId(),c.partitionId(),c.flowId(),c.instrumentId(),false).orElseThrow();
        PositionMutationResult result=new PositionMutationResult(c.fillRecordId(),decimal(c.quantity().negate()),decimal(basisReleased.negate()),decimal(realized),ending.quantity,ending.costBasis,affected);
        insertReceipt(fp,"CLOSE",result);return result;
    }

    private List<PositionLot> availableLots(CloseLongPositionCommand c){
        return jdbc.sql("""
            select l.*,p.remaining_quantity,p.remaining_cost_basis,p.version,p.closed_at,p.updated_at
            from trading.execution_position_lot l join trading.execution_position_lot_projection p on p.lot_id=l.lot_id
            where l.bot_id=:botId and l.partition_id=:partitionId and l.flow_id=:flowId and l.instrument_id=:instrumentId and p.remaining_quantity>0
            order by l.opened_at,l.lot_id for update of p
            """).param("botId",c.botId()).param("partitionId",c.partitionId()).param("flowId",c.flowId()).param("instrumentId",c.instrumentId())
            .query((rs,row)->lot(rs)).list();
    }
    private static PositionLot lot(ResultSet rs)throws SQLException{return new PositionLot(rs.getObject("lot_id",UUID.class),rs.getObject("bot_id",UUID.class),rs.getObject("partition_id",UUID.class),rs.getObject("flow_id",UUID.class),rs.getObject("instrument_id",UUID.class),rs.getObject("opening_fill_record_id",UUID.class),rs.getBigDecimal("opened_quantity"),rs.getBigDecimal("unit_price"),rs.getBigDecimal("opening_commission"),rs.getBigDecimal("opened_cost_basis"),rs.getBigDecimal("remaining_quantity"),rs.getBigDecimal("remaining_cost_basis"),rs.getObject("opened_at",OffsetDateTime.class).toInstant(),Optional.ofNullable(rs.getObject("closed_at",OffsetDateTime.class)).map(OffsetDateTime::toInstant),rs.getLong("version"));}
    private Optional<FlowProjection> flow(UUID bot,UUID partition,UUID flow,UUID instrument,boolean lock){return jdbc.sql("select quantity,cost_basis,realized_pnl,version from trading.execution_flow_position_projection where bot_id=:b and partition_id=:p and flow_id=:f and instrument_id=:i"+(lock?" for update":"")).param("b",bot).param("p",partition).param("f",flow).param("i",instrument).query((rs,row)->new FlowProjection(decimal(rs.getBigDecimal(1)),decimal(rs.getBigDecimal(2)),decimal(rs.getBigDecimal(3)),rs.getLong(4))).optional();}
    private void insertMovement(UUID fill,UUID lot,String type,BigDecimal q,BigDecimal basis,BigDecimal pnl,BigDecimal remaining,BigDecimal remainingBasis,java.time.Instant at){jdbc.sql("insert into trading.execution_position_lot_movement values(:id,:lot,:fill,:type,:q,:basis,:pnl,:remaining,:remainingBasis,:at)").param("id",UUID.nameUUIDFromBytes((fill+"|"+lot).getBytes(StandardCharsets.UTF_8))).param("lot",lot).param("fill",fill).param("type",type).param("q",q).param("basis",basis).param("pnl",pnl).param("remaining",remaining).param("remainingBasis",remainingBasis).param("at",offset(at)).update();}
    private void insertReceipt(String fp,String kind,PositionMutationResult r){jdbc.sql("insert into trading.execution_position_command values(:fill,:fp,:kind,:q,:basis,:pnl,:endingQ,:endingBasis,:lots)").param("fill",r.fillRecordId()).param("fp",fp).param("kind",kind).param("q",r.quantityDelta()).param("basis",r.costBasisDelta()).param("pnl",r.realizedPnl()).param("endingQ",r.endingQuantity()).param("endingBasis",r.endingCostBasis()).param("lots",r.affectedLots()).update();}
    private Optional<Receipt> receipt(UUID fill){return jdbc.sql("select * from trading.execution_position_command where fill_record_id=:fill").param("fill",fill).query((rs,row)->new Receipt(rs.getString("request_fingerprint"),new PositionMutationResult(rs.getObject("fill_record_id",UUID.class),decimal(rs.getBigDecimal("quantity_delta")),decimal(rs.getBigDecimal("cost_basis_delta")),decimal(rs.getBigDecimal("realized_pnl")),decimal(rs.getBigDecimal("ending_quantity")),decimal(rs.getBigDecimal("ending_cost_basis")),rs.getInt("affected_lots")))).optional();}
    private PositionMutationResult replay(Receipt r,String fp){if(!r.fingerprint.equals(fp))throw conflict("position command identity conflict");return r.result;}
    private static String fingerprint(Object... values){StringBuilder s=new StringBuilder("position-command:v1");for(Object v:values)s.append('|').append(v instanceof BigDecimal d?d.stripTrailingZeros().toPlainString():v);try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static OffsetDateTime offset(java.time.Instant at){return at.atOffset(ZoneOffset.UTC);}
    private static BigDecimal decimal(BigDecimal value){return value.stripTrailingZeros();}
    private static PositionConflictException conflict(String m){return new PositionConflictException(m);}
    private record FlowProjection(BigDecimal quantity,BigDecimal costBasis,BigDecimal realizedPnl,long version){}
    private record Receipt(String fingerprint,PositionMutationResult result){}
}
