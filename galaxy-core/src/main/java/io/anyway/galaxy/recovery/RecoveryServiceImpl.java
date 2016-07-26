package io.anyway.galaxy.recovery;

import com.alibaba.fastjson.JSON;
import io.anyway.galaxy.common.TransactionStatusEnum;
import io.anyway.galaxy.context.support.ServiceExecutePayload;
import io.anyway.galaxy.domain.TransactionInfo;
import io.anyway.galaxy.message.MessageService;
import io.anyway.galaxy.repository.TransactionRepository;
import io.anyway.galaxy.spring.DataSourceAdaptor;
import io.anyway.galaxy.spring.SpringContextUtil;
import io.anyway.galaxy.util.DateUtil;
import io.anyway.galaxy.util.ProxyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.Date;
import java.util.List;

/**
 * Created by xiong.j on 2016/7/25.
 */
@Slf4j
public class RecoveryServiceImpl implements RecoveryService{

    @Autowired
    private DataSourceAdaptor dataSourceAdaptor;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MessageService messageService;

    @Override
    public void execute(List<Integer> shardingItem) {
        Connection conn = null;
        try {
            conn = dataSourceAdaptor.getDataSource().getConnection();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 30天前
        Date searchDate = DateUtil.getPrevDate(30);

        for(Integer status : shardingItem) {
            List<TransactionInfo> transactionInfos = transactionRepository.findSince(conn, searchDate, status);
            internalExecute(transactionInfos);
        }
    }

    private void internalExecute(List<TransactionInfo> transactionInfos) {
        for(TransactionInfo info : transactionInfos) {
            if(TransactionStatusEnum.BEGIN.getCode() == info.getTxStatus()){
                // TODO BEGIN状态需要回查是否Try成功，后续优化
                try {
                    info.setTxStatus(TransactionStatusEnum.CANCELLING.getCode());
                    messageService.sendMessage(messageService.transInfo2Msg(info));
                } catch (Throwable throwable) {
                    log.warn("Send cancel message error, TransactionInfo=", info);
                }
            } else {
                ServiceExecutePayload bean = JSON.parseObject(info.getContext(), ServiceExecutePayload.class);
                Object objectClass = SpringContextUtil.getBean(bean.getTarget().getName());

                if (TransactionStatusEnum.CANCELLING.getCode() == info.getTxStatus()) {
                    try {
                        ProxyUtil.proxyMethod(objectClass, bean.getCancelMethod(), bean.getTypes(), bean.getArgs());
                    } catch (Exception e) {
                        log.warn("Process cancel error, TransactionInfo=", info);
                    }
                }

                if (TransactionStatusEnum.CONFIRMING.getCode() == info.getTxStatus()) {
                    try {
                        ProxyUtil.proxyMethod(objectClass, bean.getConfirmMethod(), bean.getTypes(), bean.getArgs());
                    } catch (Exception e) {
                        log.warn("Process confirm error, TransactionInfo=", info);
                    }
                }
            }
        }
    }
}
