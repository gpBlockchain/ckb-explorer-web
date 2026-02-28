package com.ckb.explorer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ckb.explorer.config.ScriptConfig;
import com.ckb.explorer.config.ServerException;
import com.ckb.explorer.constants.I18nKey;
import com.ckb.explorer.domain.dto.CellInputDto;
import com.ckb.explorer.domain.dto.CellOutputDto;
import com.ckb.explorer.domain.dto.DaoCellDto;
import com.ckb.explorer.domain.dto.TransactionDto;
import com.ckb.explorer.domain.req.ContractTransactionsPageReq;
import com.ckb.explorer.domain.req.UdtTransactionsPageReq;
import com.ckb.explorer.domain.resp.*;
import com.ckb.explorer.entity.CkbTransaction;
import com.ckb.explorer.entity.Script;
import com.ckb.explorer.enums.CellType;
import com.ckb.explorer.mapper.Address24hTransactionMapper;
import com.ckb.explorer.mapper.CkbTransactionMapper;
import com.ckb.explorer.mapper.DaoContractMapper;
import com.ckb.explorer.mapper.DepositCellMapper;
import com.ckb.explorer.mapper.InputMapper;
import com.ckb.explorer.mapper.OutputMapper;
import com.ckb.explorer.mapstruct.BlockTransactionConvert;
import com.ckb.explorer.mapstruct.CellInputConvert;
import com.ckb.explorer.mapstruct.CellOutputConvert;
import com.ckb.explorer.mapstruct.CkbTransactionConvert;
import com.ckb.explorer.service.BlockService;
import com.ckb.explorer.service.CkbTransactionService;
import com.ckb.explorer.service.CkbPendingTransactionService;
import com.ckb.explorer.service.ScriptService;
import com.ckb.explorer.util.CkbUtil;
import com.ckb.explorer.util.DaoCompensationCalculator;
import com.ckb.explorer.util.I18n;
import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.nervos.ckb.utils.Numeric;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CkbTransactionServiceImpl extends ServiceImpl<CkbTransactionMapper, CkbTransaction> implements
    CkbTransactionService {

  @Resource
  private I18n i18n;

  @Resource
  private InputMapper inputMapper;

  @Resource
  private OutputMapper outputMapper;

  @Resource
  private CkbTransactionMapper ckbTransactionMapper;

  @Resource
  private Address24hTransactionMapper address24hTransactionMapper;

  @Resource
  private ScriptService scriptService;

  @Resource
  private DaoContractMapper daoContractMapper;

  @Resource
  private DepositCellMapper depositCellMapper;

  @Resource
  private ScriptConfig scriptConfig;

  @Resource
  private CkbPendingTransactionService ckbPendingTransactionService;

  @Value("${ckb.daoCodeHash}")
  private String daoCodeHashList;

  @Resource
  private BlockService blockService;

  @Override
  public List<TransactionPageResponse> getHomePageTransactions(int size) {

    return baseMapper.getHomePageTransactions( size);

  }

  @Override
  public Page<TransactionPageResponse> getCkbTransactionsByPage(int pageNum, int pageSize, String sort) {

    // 创建分页对象
    Page<TransactionPageResponse> pageResult = new Page<>(pageNum, pageSize);
    pageResult.setSearchCount(false);
    var total = baseMapper.countTransactions();
    pageResult.setTotal(total);

    List<TransactionPageResponse> transactions;
    if(pageNum <= 1){
      pageResult = baseMapper.getPageTransactions(pageResult);
    } else{
      var last = total - (pageNum - 1) * pageSize + 1;
      if(last < 0){
        return pageResult;
      }
      transactions = baseMapper.getLargePageTransactions(last, pageSize);
      pageResult.setRecords( transactions);
    }
    // 执行分页查询
    return pageResult;
  }

  @Override
  public TransactionResponse getTransactionByHash(String txHash) {

    // 先查pending
    var pending = ckbPendingTransactionService.getPendingTransactionByHash(txHash);
    if(pending != null){
      return pending;
    }

    // 查询第一个匹配的交易
    TransactionDto transaction = baseMapper.selectTransactionWithCellDeps(Numeric.hexStringToByteArray(txHash));
    
    // 如果找不到交易，抛出异常
    if (transaction == null) {
      throw new ServerException(I18nKey.CKB_TRANSACTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.CKB_TRANSACTION_NOT_FOUND_MESSAGE));
    }

    TransactionResponse result = CkbTransactionConvert.INSTANCE.toConvertTransactionResponse(transaction);

    return result;
  }

  @Override
  public Page<CellInputResponse> getDisplayInputs(String txHash, int pageNum, int pageSize) {
    // 先查pending
    var pending = ckbPendingTransactionService.getDisplayInputs(txHash, pageNum, pageSize);
    if(pending != null){
      return pending;
    }

    CkbTransaction ckbTransaction = getCkbTransaction(txHash);
    // cellbase交易
    if(ckbTransaction.getTxIndex() == 0){

      return getCellbaseDisplayInputs(txHash, ckbTransaction.getBlockNumber());
      // 普通交易
    } else{
      return getNormalTxDisplayInputs(ckbTransaction.getId(), pageNum, pageSize, ckbTransaction.getBlockNumber(), ckbTransaction.getBlockTimestamp());
    }
  }

  /**
   * 处理cellbase交易的DisplayInputs
   * @param txHash
   * @param blockNumber
   * @return
   */
  private Page<CellInputResponse> getCellbaseDisplayInputs(String txHash,Long blockNumber) {
    Page<CellInputResponse> page = new Page<>(1, 1);
    List<CellInputResponse> records = new ArrayList<>();
    
    // Cellbase交易只有一个特殊的输入
    CellInputResponse cellInput = new CellInputResponse();
    cellInput.setFromCellbase(true);
    cellInput.setTargetBlockNumber(blockNumber< 11 ?0:blockNumber-11);
    cellInput.setGeneratedTxHash(txHash);
    
    records.add(cellInput);
    page.setRecords(records);
    page.setTotal(1);
    
    return page;
  }


  /**
   * 处理普通交易的DisplayInputs
   * @param transactionId
   * @param page
   * @param pageSize
   * @return
   */
  private Page<CellInputResponse> getNormalTxDisplayInputs(Long transactionId, int page, int pageSize, Long blockNumber, Long blockTimestamp) {
    Page<CellInputDto> pageResult = new Page<>(page, pageSize);
    Page<CellInputDto> resultPage = inputMapper.getDisplayInputs(pageResult, transactionId);
    resultPage.getRecords().forEach(cellInputDto -> {
      ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellInputDto.getCodeHash(),cellInputDto.getArgs());
      Integer cellType = scriptConfig.cellType(typeScript,Numeric.toHexString(cellInputDto.getData()));
      cellInputDto.setCellType(cellType);
    });

    List<CellInputDto> inputDtos = resultPage.getRecords();
    for(CellInputDto input: inputDtos){
      // 如果是Dao phase1交易的input
      if(input.getCellType() != null && input.getCellType() == CellType.NERVOS_DAO_DEPOSIT.getValue()){
        input.setNervosDaoInfo(attributesForDaoInput(input,false, blockNumber, blockTimestamp));
      }
      // 如果是Dao phase2交易的input5
      if(input.getCellType() != null && input.getCellType() == CellType.NERVOS_DAO_WITHDRAWING.getValue()){
        input.setNervosDaoInfo(attributesForDaoInput(input,true, blockNumber, blockTimestamp));
      }
    }
    return CellInputConvert.INSTANCE.toConvertPage(resultPage);
  }

  @Override
  public Page<CellOutputResponse> getDisplayOutputs(String txHash, int pageNum, int pageSize) {

    // 先查pending
    var pending = ckbPendingTransactionService.getDisplayOutputs(txHash, pageNum, pageSize);
    if(pending != null){
      return pending;
    }

    CkbTransaction ckbTransaction = getCkbTransaction(txHash);
    Page<CellOutputDto> pageResult = new Page<>(pageNum, pageSize);
    // cellbase交易
    if(ckbTransaction.getTxIndex() == 0){

      Page<CellOutputDto> resultPage = outputMapper.getCellbaseDisplayOutputs(pageResult, ckbTransaction.getId());
      resultPage.getRecords().forEach(cellOutputDto -> {
        if(StringUtils.isNotEmpty(cellOutputDto.getCodeHash())){
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellOutputDto.getCodeHash(),cellOutputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript,Numeric.toHexString(cellOutputDto.getData()));
          cellOutputDto.setCellType(cellType);
        }

      });

      Page<CellOutputResponse> result = CellOutputConvert.INSTANCE.toConvertPage(resultPage);
      List<CellOutputResponse> records = result.getRecords();
      records.stream().forEach(record-> record.setTargetBlockNumber(ckbTransaction.getBlockNumber()< 11 ?0:ckbTransaction.getBlockNumber()-11));
      return result;
      // 普通交易
    } else {
      Page<CellOutputDto> resultPage = outputMapper.getNormalTxDisplayOutputs(pageResult, ckbTransaction.getId());
      resultPage.getRecords().forEach(cellOutputDto -> {
        if(StringUtils.isNotEmpty(cellOutputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellOutputDto.getCodeHash(), cellOutputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellOutputDto.getData()));
          cellOutputDto.setCellType(cellType);
        }
      });
      return CellOutputConvert.INSTANCE.toConvertPage(resultPage);
    }
  }

  private CkbTransaction getCkbTransaction(String txHash) {
    LambdaQueryWrapper<CkbTransaction> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CkbTransaction::getTxHash, Numeric.hexStringToByteArray(txHash));
    return baseMapper.selectOne(queryWrapper);
  }

  private Script getAddress(String addressHash) {
    // 计算地址的哈希
    return scriptService.getAddress(addressHash);
  }

  private Script getDaoTypeScript(){
    var codeHashs = Arrays.stream(daoCodeHashList.split( ",")).toList();
    var codeHash = codeHashs.stream().map(Numeric::hexStringToByteArray).collect(Collectors.toList());
    return scriptService.getDaoTypeScript(codeHash);
  }
  @Override
  public Page<BlockTransactionPageResponse> getBlockTransactions(String blockHash, String txHash,
      String addressHash, int page, int pageSize) {

    // 创建分页对象
    Page<CkbTransaction> transactionPage = new Page<>(page, pageSize);

    Long lockScriptId = null;
    // 如果指定地址
    if (addressHash != null && !addressHash.isEmpty()) {
      // 查找地址
      var script = getAddress(addressHash);
      if(script == null){
        throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
      }
      lockScriptId = script.getId();
    }

    // 执行分页查询
    Page<CkbTransaction> resultPage = ckbTransactionMapper.selectPageByBlockHash(transactionPage, Numeric.hexStringToByteArray(blockHash), txHash != null && !txHash.isEmpty() ?Numeric.hexStringToByteArray(txHash): null, lockScriptId);

    var result = BlockTransactionConvert.INSTANCE.toConvertPage(resultPage);

    var transactions = result.getRecords();
    // 分开处理cellbase和普通交易
    var cellbaseTransactionsIds = transactions.stream().filter(transaction-> transaction.getIsCellbase()).map(BlockTransactionPageResponse::getId).collect(Collectors.toList());
    var normalTransactionsIds = transactions.stream().filter(transaction-> !transaction.getIsCellbase()).map(BlockTransactionPageResponse::getId).collect(Collectors.toList());

    Map<Long,List<CellOutputDto>>  cellbaseOutputsWithTrans;
    Map<Long,List<CellInputDto>> normalInputsWithTrans ;
    Map<Long,List<CellOutputDto>>  normalOutputsWithTrans;

    // cellbase获取output
    if(cellbaseTransactionsIds.size() > 0){
      cellbaseOutputsWithTrans = outputMapper.getCellbaseDisplayOutputsByTransactionIds(cellbaseTransactionsIds).stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else {
      cellbaseOutputsWithTrans = new HashMap<>();
    }

    // 普通交易获取input和output
    if(normalTransactionsIds.size() > 0){
      List<CellInputDto> normalInputs = inputMapper.getNormalDisplayInputsByTransactionIds(normalTransactionsIds, pageSize);
      normalInputs.forEach(cellInputDto -> {
        if(StringUtils.isNotEmpty(cellInputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellInputDto.getCodeHash(), cellInputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellInputDto.getData()));
          cellInputDto.setCellType(cellType);
        }
      });
      normalInputsWithTrans = normalInputs.stream().collect(Collectors.groupingBy(CellInputDto::getTransactionId));
      List<CellOutputDto> normalOutputs = outputMapper.getNormalTxDisplayOutputsByTransactionIds(normalTransactionsIds, pageSize);
      normalOutputs.forEach(cellOutputDto -> {
        if(StringUtils.isNotEmpty(cellOutputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellOutputDto.getCodeHash(), cellOutputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellOutputDto.getData()));
          cellOutputDto.setCellType(cellType);
        }
      });
      normalOutputsWithTrans = normalOutputs.stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else{
      normalInputsWithTrans = new HashMap<>();
      normalOutputsWithTrans = new HashMap<>();
    }

    transactions.stream().forEach(transaction -> {
      // 组装cellbase交易
      if(transaction.getIsCellbase()){
        Long targetBlockNumber = transaction.getBlockNumber()< 11 ?0:transaction.getBlockNumber()-11;
        List<CellInputResponse> records = new ArrayList<>();

        // Cellbase交易只有一个特殊的输入
        CellInputResponse cellInput = new CellInputResponse();
        cellInput.setFromCellbase(true);
        cellInput.setTargetBlockNumber(targetBlockNumber);
        cellInput.setGeneratedTxHash(transaction.getTransactionHash());

        records.add(cellInput);
        transaction.setDisplayInputs(records);

        List<CellOutputResponse> cellbaseOutput = CellOutputConvert.INSTANCE.INSTANCE.toConvertList(cellbaseOutputsWithTrans.get(transaction.getId()));
        if(cellbaseOutput != null && cellbaseOutput.size() > 0){
          cellbaseOutput.stream().forEach(record-> record.setTargetBlockNumber(targetBlockNumber));
        } else{
          cellbaseOutput = new ArrayList<>();
        }


        transaction.setDisplayOutputs(cellbaseOutput);
        // 组装普通交易
      }else{
        transaction.setDisplayInputs(CellInputConvert.INSTANCE.toConvertList(
            normalInputsWithTrans.get(transaction.getId())));

        transaction.setDisplayOutputs(CellOutputConvert.INSTANCE.INSTANCE.toConvertList(
            normalOutputsWithTrans.get(transaction.getId())));
      }
    });

    return result;
  }


  @Override
  public Page<AddressTransactionPageResponse> getAddressTransactions(String address, String sort,
      int page, int pageSize, LocalDate startTime, LocalDate endTime) {

    String[] sortParts = sort.split("\\.", 2);
    String orderBy = sortParts[0];
    String ascOrDesc = sortParts.length > 1 ? sortParts[1].toLowerCase() : "desc";
    if (!"asc".equals(ascOrDesc) && !"desc".equals(ascOrDesc)) {
      ascOrDesc = "desc";
    }

    orderBy = switch (orderBy) {
      case "time" -> "block_timestamp";
      default -> throw new ServerException(i18n.getMessage(I18nKey.SORT_ERROR_MESSAGE));
    };

    // 判断地址是否存在
    var script = scriptService.getAddress(address);
    if (script == null) {
      throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE,
          i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
    }


    Long startTimeLong = 0L;
    Long endTimeLong = 0L;
    // 如果没有指定开始结束时间，则获取最近一个月的交易
    if(startTime == null && endTime == null){

      ZonedDateTime currentUtc = ZonedDateTime.now(ZoneOffset.UTC);
      LocalDate firstDayOfMonthUtc = currentUtc.toLocalDate()
          .with(TemporalAdjusters.firstDayOfMonth()); // 当月第一天
      ZonedDateTime firstDayStartUtc = firstDayOfMonthUtc
          .atStartOfDay(ZoneOffset.UTC); // 00:00:00 UTC

      startTimeLong = firstDayStartUtc.toInstant().toEpochMilli();

      LocalDate lastDayOfMonthUtc = currentUtc.toLocalDate()
          .with(TemporalAdjusters.lastDayOfMonth()); // 当月最后一天
      ZonedDateTime lastDayEndUtc = lastDayOfMonthUtc
          .atTime(23, 59, 59) // 23:59:59
          .with(ChronoField.MILLI_OF_SECOND, 999)
          .atZone(ZoneOffset.UTC); // 绑定UTC时区
      endTimeLong = lastDayEndUtc.toInstant().toEpochMilli();
      // 如果指定了开始结束时间，则获取指定时间段的交易
    } else if(startTime != null && endTime != null){

      // 转换成UTC时间毫秒
      startTimeLong = startTime.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
      endTimeLong = endTime.atTime(23, 59, 59).with(ChronoField.MILLI_OF_SECOND, 999).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    Long total = baseMapper.selectTotalByAddressScriptId(script.getId(), startTimeLong, endTimeLong);
    if (total == 0){
      return Page.of(page, pageSize, 0);
    }

    Page transactionPage = new Page<>(page, pageSize);
    transactionPage.setSearchCount(false);
    Page<String> transactionHashPage = baseMapper.selectHashPageByAddressScriptId(transactionPage, orderBy, ascOrDesc,script.getId(), startTimeLong, endTimeLong);

    List<byte[]> txHashes = transactionHashPage.getRecords().stream().map(txHash -> Numeric.hexStringToByteArray(txHash)).toList();
    List<AddressTransactionPageResponse> transactions = baseMapper.selectByTxHashes(orderBy, ascOrDesc, txHashes, startTimeLong, endTimeLong);

    // 分开处理cellbase和普通交易
    var cellbaseTransactionsIds = transactions.stream().filter(transaction-> transaction.getIsCellbase()).map(AddressTransactionPageResponse::getId).collect(Collectors.toList());
    var normalTransactionsIds = transactions.stream().filter(transaction-> !transaction.getIsCellbase()).map(AddressTransactionPageResponse::getId).collect(Collectors.toList());

    Map<Long,List<CellOutputDto>>  cellbaseOutputsWithTrans;
    Map<Long,List<CellInputDto>> normalInputsWithTrans ;
    Map<Long,List<CellOutputDto>>  normalOutputsWithTrans;
    // cellbase获取output
    if(cellbaseTransactionsIds.size() > 0){
      cellbaseOutputsWithTrans = outputMapper.getCellbaseDisplayOutputsByTransactionIds(cellbaseTransactionsIds).stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else{
      cellbaseOutputsWithTrans = new HashMap<>();
    }

    // 普通交易获取input和output
    if(normalTransactionsIds.size() > 0){
      List<CellInputDto> normalInputs = inputMapper.getNormalDisplayInputsByTransactionIds(normalTransactionsIds, pageSize);
      normalInputs.forEach(cellInputDto -> {
        if(StringUtils.isNotEmpty(cellInputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellInputDto.getCodeHash(), cellInputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellInputDto.getData()));
          cellInputDto.setCellType(cellType);
        }
      });
      normalInputsWithTrans = normalInputs.stream().collect(Collectors.groupingBy(CellInputDto::getTransactionId));
      List<CellOutputDto> normalOutputs = outputMapper.getNormalTxDisplayOutputsByTransactionIds(normalTransactionsIds, pageSize);
      normalOutputs.forEach(cellOutputDto -> {
        if(StringUtils.isNotEmpty(cellOutputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellOutputDto.getCodeHash(), cellOutputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellOutputDto.getData()));
          cellOutputDto.setCellType(cellType);
        }
      });
      normalOutputsWithTrans = normalOutputs.stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else{
      normalInputsWithTrans = new HashMap<>();
      normalOutputsWithTrans = new HashMap<>();
    }

    transactions.stream().forEach(transaction -> {
      // 组装cellbase交易
      if(transaction.getIsCellbase()){
        Long targetBlockNumber = transaction.getBlockNumber()< 11 ?0:transaction.getBlockNumber()-11;
        List<CellInputResponse> records = new ArrayList<>();

        // Cellbase交易只有一个特殊的输入
        CellInputResponse cellInput = new CellInputResponse();
        cellInput.setFromCellbase(true);
        cellInput.setTargetBlockNumber(targetBlockNumber);
        cellInput.setGeneratedTxHash(transaction.getTransactionHash());

        records.add(cellInput);
        transaction.setDisplayInputs(records);

        List<CellOutputResponse> cellbaseOutput = CellOutputConvert.INSTANCE.INSTANCE.toConvertList(cellbaseOutputsWithTrans.get(transaction.getId()));
        if(cellbaseOutput != null && cellbaseOutput.size() > 0){
          cellbaseOutput.stream().forEach(record-> record.setTargetBlockNumber(targetBlockNumber));
        }else{
          cellbaseOutput = new ArrayList<>();
        }

        transaction.setDisplayOutputs(cellbaseOutput);
        // 组装普通交易
      }else{
        transaction.setDisplayInputs(CellInputConvert.INSTANCE.toConvertList(
            normalInputsWithTrans.get(transaction.getId())));

        transaction.setDisplayOutputs(CellOutputConvert.INSTANCE.INSTANCE.toConvertList(
            normalOutputsWithTrans.get(transaction.getId())));
      }
    });

    var result = new Page<AddressTransactionPageResponse>(page, pageSize, total);
    result.setRecords(transactions);
    return result;
  }

  @Override
  public Page<UdtTransactionPageResponse> getUdtTransactions(String typeScriptHash, UdtTransactionsPageReq req) {
    Integer page = req.getPage();
    Integer pageSize = req.getPageSize();
    String[] sortParts = req.getSort().split("\\.", 2);
    String orderBy = sortParts[0];
    String ascOrDesc = sortParts.length > 1 ? sortParts[1].toLowerCase() : "desc";
    if (!"asc".equals(ascOrDesc) && !"desc".equals(ascOrDesc)) {
      ascOrDesc = "desc";
    }

    orderBy = switch (orderBy) {
      case "time" -> "block_timestamp";
      default -> throw new ServerException(i18n.getMessage(I18nKey.SORT_ERROR_MESSAGE));
    };

    // 判断Udt是否存在
    Script script = scriptService.findByScriptHash(typeScriptHash);
    if (script == null) {
      throw new ServerException(I18nKey.UDTS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.UDTS_NOT_FOUND_MESSAGE));
    }

    Long lockScriptId = null;
    if (StringUtils.isNotEmpty(req.getAddressHash())) {
      // 查找地址
      var lockScript = getAddress(req.getAddressHash());
      if(lockScript == null){
        throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
      }
      lockScriptId = lockScript.getId();
    }

    byte[] txHash = null;
    if(StringUtils.isNotEmpty(req.getTxHash())){
      txHash = Numeric.hexStringToByteArray(req.getTxHash());
    }

    // 从24小时表里获取翻页的交易id
    Page<Long> transactionIdsPage = new Page<>(page, pageSize);
    Page<Long> transactionIdPage = outputMapper.getUdtTransactionHashes(
            transactionIdsPage, script.getId(), orderBy, ascOrDesc,txHash,lockScriptId);

    List<Long> transactionIds = transactionIdPage.getRecords();
    if (transactionIds.isEmpty()){
      return Page.of(page, pageSize, 0);
    }

    // 根据交易id查询交易详情
    List<UdtTransactionPageResponse> transactions = CkbTransactionConvert.INSTANCE.toConvertUdtTransactionList(baseMapper.selectByTransactionIds(transactionIds, orderBy, ascOrDesc));

    // 分开处理cellbase和普通交易
    var cellbaseTransactionsIds = transactions.stream().filter(transaction-> transaction.getIsCellbase()).map(UdtTransactionPageResponse::getId).collect(Collectors.toList());
    var normalTransactionsIds = transactions.stream().filter(transaction-> !transaction.getIsCellbase()).map(UdtTransactionPageResponse::getId).collect(Collectors.toList());

    Map<Long,List<CellOutputDto>>  cellbaseOutputsWithTrans;
    Map<Long,List<CellInputDto>> normalInputsWithTrans ;
    Map<Long,List<CellOutputDto>>  normalOutputsWithTrans;
    // cellbase获取output
    if(cellbaseTransactionsIds.size() > 0){
      cellbaseOutputsWithTrans = outputMapper.getCellbaseDisplayOutputsByTransactionIds(cellbaseTransactionsIds).stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else{
      cellbaseOutputsWithTrans = new HashMap<>();
    }

    // 普通交易获取input和output
    if(normalTransactionsIds.size() > 0){
      List<CellInputDto> normalInputs = inputMapper.getNormalDisplayInputsByTransactionIds(normalTransactionsIds, pageSize);
      normalInputs.forEach(cellInputDto -> {
        if(StringUtils.isNotEmpty(cellInputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellInputDto.getCodeHash(), cellInputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellInputDto.getData()));
          cellInputDto.setCellType(cellType);
        }
      });
      normalInputsWithTrans = normalInputs.stream().collect(Collectors.groupingBy(CellInputDto::getTransactionId));
      List<CellOutputDto> normalOutputs = outputMapper.getNormalTxDisplayOutputsByTransactionIds(normalTransactionsIds, pageSize);
      normalOutputs.forEach(cellOutputDto -> {
        if(StringUtils.isNotEmpty(cellOutputDto.getCodeHash())) {
          ScriptConfig.TypeScript typeScript = scriptConfig.getTypeScriptByCodeHash(cellOutputDto.getCodeHash(), cellOutputDto.getArgs());
          Integer cellType = scriptConfig.cellType(typeScript, Numeric.toHexString(cellOutputDto.getData()));
          cellOutputDto.setCellType(cellType);
        }
      });
      normalOutputsWithTrans = normalOutputs.stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else{
      normalInputsWithTrans = new HashMap<>();
      normalOutputsWithTrans = new HashMap<>();
    }

    transactions.stream().forEach(transaction -> {
      // 组装cellbase交易
      if(transaction.getIsCellbase()){
        Long targetBlockNumber = transaction.getBlockNumber()< 11 ?0:transaction.getBlockNumber()-11;
        List<CellInputResponse> records = new ArrayList<>();

        // Cellbase交易只有一个特殊的输入
        CellInputResponse cellInput = new CellInputResponse();
        cellInput.setFromCellbase(true);
        cellInput.setTargetBlockNumber(targetBlockNumber);
        cellInput.setGeneratedTxHash(transaction.getTransactionHash());

        records.add(cellInput);
        transaction.setDisplayInputs(records);

        List<CellOutputResponse> cellbaseOutput = CellOutputConvert.INSTANCE.INSTANCE.toConvertList(cellbaseOutputsWithTrans.get(transaction.getId()));
        if(cellbaseOutput != null && cellbaseOutput.size() > 0){
          cellbaseOutput.stream().forEach(record-> record.setTargetBlockNumber(targetBlockNumber));
        }else{
          cellbaseOutput = new ArrayList<>();
        }

        transaction.setDisplayOutputs(cellbaseOutput);
        // 组装普通交易
      }else{
        transaction.setDisplayInputs(CellInputConvert.INSTANCE.toConvertList(
                normalInputsWithTrans.get(transaction.getId())));

        transaction.setDisplayOutputs(CellOutputConvert.INSTANCE.INSTANCE.toConvertList(
                normalOutputsWithTrans.get(transaction.getId())));
      }
    });

    var result = new Page<UdtTransactionPageResponse>(transactionIdPage.getCurrent(), transactionIdPage.getSize(), transactionIdPage.getTotal());
    result.setRecords(transactions);
    return result;
  }

  @Override
  public Page<ContractTransactionPageResponse> getContractTransactions(ContractTransactionsPageReq req) {

    Integer page = req.getPage();
    Integer pageSize = req.getPageSize();
    Page<ContractTransactionPageResponse> resultPage = new Page<>(page, pageSize);
    // 查DaoContract
    var daoContract = daoContractMapper.defaultContract();
    if(daoContract == null){
      return resultPage.setTotal(0);
    }

    // 处理txHash参数
    byte[] txHash = null;
    if (StringUtils.isNotEmpty(req.getTxHash())) {
      var ckbTransaction = getCkbTransaction(req.getTxHash());
      if (ckbTransaction == null) {
        throw new ServerException(I18nKey.CKB_TRANSACTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.CKB_TRANSACTION_NOT_FOUND_MESSAGE));
      }
      txHash = Numeric.hexStringToByteArray(req.getTxHash());
    }

    // 查询Dao 的typeScriptId
    var typeScript = getDaoTypeScript();
    Long typeScriptId = typeScript.getId();
    if(typeScriptId == null){
      return resultPage.setTotal(0);
    }

    // 处理addressHash参数
    Long lockScriptId = null;
    if (StringUtils.isNotEmpty(req.getAddressHash())) {
      var script = getAddress(req.getAddressHash());
      if (script == null) {
        throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
      }
      lockScriptId = script.getId();
    }

    List<ContractTransactionPageResponse> transactions ;
    if(lockScriptId != null){
      Page txPage = new Page<>(page, pageSize);
      resultPage = baseMapper.getPageContractTransactions(txPage, lockScriptId, typeScriptId);
      transactions = resultPage.getRecords();

      // 查具体交易信息
      if (transactions.isEmpty()) {
        return resultPage;
      }
    } else{
      Page txHashPage = new Page<>(page, pageSize);
      // 从数据库查询合约交易 从Cell里查
      txHashPage = depositCellMapper.getTxHashPage(txHashPage, txHash);
      List<byte[]> txHashs = txHashPage.getRecords();
      if(txHashs.isEmpty()){
        return resultPage.setTotal(0);
      }
      transactions = baseMapper.selectContractTransactions(txHashs);

      // 查具体交易信息
      if (transactions.isEmpty()) {
        return resultPage.setTotal(0);
      }

      resultPage.setTotal(txHashPage.getTotal());
    }

    // 分开处理cellbase和普通交易
    var normalTransactionsIds = transactions.stream().map(ContractTransactionPageResponse::getId).collect(Collectors.toList());

    Map<Long,List<CellInputDto>> normalInputsWithTrans ;
    Map<Long,List<CellOutputDto>>  normalOutputsWithTrans;

    // 普通交易获取input和output
    if(normalTransactionsIds.size() > 0){
      normalInputsWithTrans = inputMapper.getDaoDisplayInputsByTransactionIds(normalTransactionsIds, pageSize, typeScriptId).stream().collect(Collectors.groupingBy(CellInputDto::getTransactionId));
      normalOutputsWithTrans = outputMapper.getDaoDisplayOutputsByTransactionIds(normalTransactionsIds, pageSize, typeScriptId).stream().collect(Collectors.groupingBy(CellOutputDto::getTransactionId));
    } else {
      normalInputsWithTrans = new HashMap<>();
      normalOutputsWithTrans = new HashMap<>();
    }

    transactions.stream().forEach(transaction -> {

      // 组装交易
      List<CellInputResponse> inputs = CellInputConvert.INSTANCE.toConvertList(normalInputsWithTrans.get(transaction.getId()));
      transaction.setDisplayInputs(inputs);
      transaction.setDisplayInputsCount(inputs != null ? inputs.size() : 0);

      List<CellOutputResponse> outputs = CellOutputConvert.INSTANCE.INSTANCE.toConvertList(normalOutputsWithTrans.get(transaction.getId()));
      transaction.setDisplayOutputs(outputs);
      transaction.setDisplayOutputsCount(outputs != null ? outputs.size() : 0);

    });

    resultPage.setRecords( transactions);

    return resultPage;
  }

  /**
   * 计算Dao交易的input信息
   * @param nervosDaoWithdrawingCell
   * @param isPhase2
   * @param currentBlockNumber
   * @param currentBlockTimestamp
   * @return
   */
  private NervosDaoInfoResponse attributesForDaoInput(CellInputDto nervosDaoWithdrawingCell,  boolean isPhase2, Long currentBlockNumber, Long currentBlockTimestamp) {
    NervosDaoInfoResponse nervosDaoInfo = new NervosDaoInfoResponse();

    var compensationStartedBlockNumber = 0L;
    var compensationEndedBlockNumber = 0L;
    // 如果是phase2交易
    if(isPhase2){
      // 起始块高为cell的data
      compensationStartedBlockNumber = CkbUtil.convertToBlockNumber(nervosDaoWithdrawingCell.getData());
      // 终止块高为cell的块高，即phase1的output的块高
      compensationEndedBlockNumber = nervosDaoWithdrawingCell.getBlockNumber();

      // 锁定终止块高为当前交易块高
      nervosDaoInfo.setLockedUntilBlockNumber(currentBlockNumber);
      nervosDaoInfo.setLockedUntilBlockTimestamp(currentBlockTimestamp);

      // 否则是phase1交易
    } else{
      // 起始块高为cell的块高，即deposit的output的块高
      compensationStartedBlockNumber = nervosDaoWithdrawingCell.getBlockNumber();
      // 终止块高为当前交易块高
      compensationEndedBlockNumber = currentBlockNumber;
    }

    var compensationStartedBlock = blockService.getDaoBlockByBlockNumber(compensationStartedBlockNumber);
    nervosDaoInfo.setCompensationStartedBlockNumber(compensationStartedBlock.getBlockNumber());
    nervosDaoInfo.setCompensationStartedTimestamp(compensationStartedBlock.getTimestamp());

    var compensationEndedBlock = blockService.getDaoBlockByBlockNumber(compensationEndedBlockNumber);
    nervosDaoInfo.setCompensationEndedBlockNumber(compensationEndedBlock.getBlockNumber());
    nervosDaoInfo.setCompensationEndedTimestamp(compensationEndedBlock.getTimestamp());

    DaoCellDto dapCell = new DaoCellDto();
    dapCell.setValue(nervosDaoWithdrawingCell.getCapacity());
    dapCell.setOccupiedCapacity(nervosDaoWithdrawingCell.getOccupiedCapacity());
    nervosDaoInfo.setInterest(DaoCompensationCalculator.call(dapCell, compensationEndedBlock.getDao(),
        compensationStartedBlock.getDao()));
    return nervosDaoInfo;
  }
}
