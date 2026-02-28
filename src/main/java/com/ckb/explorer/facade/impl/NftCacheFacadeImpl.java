package com.ckb.explorer.facade.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ckb.explorer.config.ScriptConfig;
import com.ckb.explorer.config.ServerException;
import com.ckb.explorer.constants.I18nKey;
import com.ckb.explorer.domain.CollectionsDto;
import com.ckb.explorer.domain.dto.AccountNftDto;
import com.ckb.explorer.domain.dto.NftHolderDto;
import com.ckb.explorer.domain.dto.NftItemDto;
import com.ckb.explorer.domain.dto.NftTransfersDto;
import com.ckb.explorer.domain.req.CollectionsPageReq;
import com.ckb.explorer.domain.req.NftHoldersPageReq;
import com.ckb.explorer.domain.req.NftTransfersPageReq;
import com.ckb.explorer.domain.req.base.BasePageReq;
import com.ckb.explorer.domain.resp.*;
import com.ckb.explorer.entity.DobCode;
import com.ckb.explorer.entity.Output;
import com.ckb.explorer.entity.OutputData;
import com.ckb.explorer.entity.Script;
import com.ckb.explorer.enums.LockType;
import com.ckb.explorer.enums.NftAction;
import com.ckb.explorer.enums.NftType;
import com.ckb.explorer.facade.INftCacheFacade;
import com.ckb.explorer.mapper.*;
import com.ckb.explorer.mapstruct.NftConvert;
import com.ckb.explorer.util.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nervos.ckb.utils.Numeric;
import org.nervos.ckb.utils.address.Address;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NftCacheFacadeImpl implements INftCacheFacade {


    @Resource
    private I18n i18n;

    private static final String COLLECTION_CACHE_PREFIX = "nft:collections:";

    private static final String TRANSFERS_CACHE_PREFIX = "nft:transfers:";

    private static final String HOLDERS_CACHE_PREFIX = "nft:holders:";

    private static final String ITEMS_CACHE_PREFIX = "nft:items:";

    private static final String ITEM_INFO_CACHE_PREFIX = "nft:itemInfo:";


    private static final String ACCOUNT_PREFIX = "nft:accounts:";

    private static final String STORE_CELL_PREFIX = "nft:storeCell:";


    private static final String CACHE_VERSION = "v1";

    @Resource
    DobExtendMapper dobExtendMapper;
    @Resource
    private CacheUtils cacheUtils;

    @Resource
    DobCodeMapper dobCodeMapper;

    // 缓存 TTL：10 秒
    private static final long TTL_SECONDS = 10;

    @Resource
    ScriptMapper scriptMapper;

    @Resource
    OutputDataMapper outputDataMapper;

    @Resource
    ScriptConfig scriptConfig;

    @Resource
    OutputMapper outputMapper;

    @Override
    public Page<CollectionsResp> collectionsPage(CollectionsPageReq req) {
        req.setSort(StringUtils.defaultIfBlank(req.getSort(), "block_timestamp.desc"));
        String cacheKey = String.format("%s%s:sort:%s:page:%d:size:%d:tags:%s:standard:%s",
                COLLECTION_CACHE_PREFIX, CACHE_VERSION, req.getSort(), req.getPage(), req.getPageSize(), req.getTags(),req.getStandard());

        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> loadCollectionsFromDatabase(req),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }

    private Page<CollectionsResp> loadCollectionsFromDatabase(CollectionsPageReq req) {
        Page<CollectionsDto> collectionsPage = new Page<>(req.getPage(), req.getPageSize());
        String[] sortParts = req.getSort().split("\\.", 2);
        String orderBy = sortParts[0];
        if (!Pattern.compile("^(block_timestamp|h24_ckb_transactions_count|holders_count|items_count)$").matcher(orderBy).matches()) {
            orderBy = "block_timestamp";
        }
        String ascOrDesc = sortParts.length > 1 ? sortParts[1].toLowerCase() : "desc";
        if (!"asc".equals(ascOrDesc) && !"desc".equals(ascOrDesc)) {
            ascOrDesc = "desc";
        }
        Long oneDayAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        List<String> tags = null;
        collectionsPage = dobExtendMapper.dobPage(collectionsPage, oneDayAgo, orderBy, ascOrDesc, req.getTags(),NftType.getCodeByValueAllowNull(req.getStandard()));
        return NftConvert.INSTANCE.toNftCollectionsRespPage(collectionsPage);
    }


    @Override
    public CollectionsResp findByTypeScriptHash(String typeScriptHash) {
        CollectionsDto collectionsDto = dobExtendMapper.findDetailByScriptHash(Numeric.hexStringToByteArray(typeScriptHash));
        CollectionsResp collectionsResp = NftConvert.INSTANCE.toCollectionsResp(collectionsDto);
        if (collectionsResp == null) {
            throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
        }
        return collectionsResp;
    }


    @Override
    public Page<NftTransfersResp> nftTransfersPage(String typeScriptHash, NftTransfersPageReq req) {
        String cacheKey = String.format("%s%s:%s:page:%d:size:%d:txHash:%s:addressHash:%s:tokenId:%s:action:%s",
                TRANSFERS_CACHE_PREFIX, CACHE_VERSION, typeScriptHash, req.getPage(), req.getPageSize(), req.getTxHash(), req.getAddressHash(), req.getTokenId(), req.getAction());
        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> loadTransfersFromDatabase(typeScriptHash, req),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }


    private Page<NftTransfersResp> loadTransfersFromDatabase(String typeScriptHash, NftTransfersPageReq req) {
        CollectionsDto collectionsDto = dobExtendMapper.findDetailByScriptHash(Numeric.hexStringToByteArray(typeScriptHash));
        if (collectionsDto == null) {
            throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
        }
        Page<NftTransfersDto> nftTransfersDtoPage = new Page<>(req.getPage(), req.getPageSize());

        byte[] txHash = null;
        Long lockScriptId = null;
        if (org.springframework.util.StringUtils.hasLength(req.getTxHash())) {
            txHash = Numeric.hexStringToByteArray(req.getTxHash());
        }
        if (org.springframework.util.StringUtils.hasLength(req.getAddressHash())) {
            // 查找地址
            // 计算地址的哈希
            var lockScript = getAddressScript(req.getAddressHash());

            lockScriptId = lockScript.getId();
        }

        Long typeScriptId = null;
        if (org.springframework.util.StringUtils.hasLength(req.getTokenId())) {
            byte[] dobCodeArgs;
            if(Objects.equals(NftType.M_NFT.getCode(),collectionsDto.getStandard())){
                byte[] tokenByte = Numeric.toBytesPadded(new BigInteger(req.getTokenId()),4);
                dobCodeArgs = CkbUtil.concatWithByteBuffer(collectionsDto.getArgs(),tokenByte);
            }else {
                dobCodeArgs = Numeric.hexStringToByteArray(req.getTokenId());
            }
            DobCode dobCode = dobCodeMapper.findByDobCodeScriptArgs(dobCodeArgs);
            if (dobCode == null) {
                throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
            }
            typeScriptId = dobCode.getDobCodeScriptId();
        }

        Integer action = null;
        if (org.springframework.util.StringUtils.hasLength(req.getAction())) {
            action = NftAction.getCodeByValue(req.getAction());
        }

        Page<NftTransfersDto> page = dobExtendMapper.transfersPage(nftTransfersDtoPage, Numeric.hexStringToByteArray(typeScriptHash), txHash, lockScriptId, typeScriptId, action);
        Set<Long> scriptIds = new HashSet<>();
        page.getRecords().forEach(nftTransfersDto -> {
            if (nftTransfersDto.getFtLockScriptId() != null) {
                scriptIds.add(nftTransfersDto.getFtLockScriptId());
            }
            scriptIds.add(nftTransfersDto.getLockScriptId());
            scriptIds.add(nftTransfersDto.getTypeScriptId());
        });
        if (scriptIds.isEmpty()) {
            return Page.of(req.getPage(), req.getPageSize(), 0);
        }
        List<Script> scripts = scriptMapper.selectByIds(scriptIds);
        page.getRecords().forEach(nftTransfersDto -> {
            Script ftLockScript = null;
            if (nftTransfersDto.getFtLockScriptId() != null) {
                ftLockScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftTransfersDto.getFtLockScriptId())).findFirst().orElse(null);
            }
            Script lockScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftTransfersDto.getLockScriptId())).findFirst().orElse(null);
            String from = null;
            String to = null;
            if (nftTransfersDto.getIsSpent() == 0) {
                to = TypeConversionUtil.scriptToAddress(lockScript.getCodeHash(), lockScript.getArgs(), lockScript.getHashType());
            } else if (nftTransfersDto.getIsSpent() == 1) {
                if (ftLockScript != null) {
                    to = TypeConversionUtil.scriptToAddress(ftLockScript.getCodeHash(), ftLockScript.getArgs(), ftLockScript.getHashType());
                }
                from = TypeConversionUtil.scriptToAddress(lockScript.getCodeHash(), lockScript.getArgs(), lockScript.getHashType());
            }
            nftTransfersDto.setFrom(from);
            nftTransfersDto.setTo(to);
            nftTransfersDto.setStandard(collectionsDto.getStandard());
            Script typeScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftTransfersDto.getTypeScriptId())).findFirst().orElse(null);
            if(Objects.equals(NftType.M_NFT.getCode(),collectionsDto.getStandard())){
                nftTransfersDto.setTokenId(Numeric.toBigInt(Arrays.copyOfRange(typeScript.getArgs(),24,typeScript.getArgs().length))+"");
                nftTransfersDto.setIconUrl(collectionsDto.getIconUrl());
            }else {
                nftTransfersDto.setTokenId(Numeric.toHexString(typeScript.getArgs()));
            }

        });
        setBigDataForNftTransfer(page.getRecords());
        Page<NftTransfersResp> transfersRespPage = NftConvert.INSTANCE.toNftTransfersRespPage(page);
        return transfersRespPage;
    }

    @Override
    public Page<NftHolderResp> nftHolders(String typeScriptHash, NftHoldersPageReq req) {
        req.setSort(StringUtils.defaultIfBlank(req.getSort(), "holders_count.desc"));

        String cacheKey = String.format("%s%s:%s:page:%d:size:%d:addressHash:%s:sort:%s",
                HOLDERS_CACHE_PREFIX, CACHE_VERSION, typeScriptHash, req.getPage(), req.getPageSize(), req.getAddressHash(), req.getSort());
        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> loadNftHoldersFromDatabase(typeScriptHash, req),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }

    private Page<NftHolderResp> loadNftHoldersFromDatabase(String typeScriptHash, NftHoldersPageReq req) {
        String[] sortParts = req.getSort().split("\\.", 2);
        String orderBy = sortParts[0];
        if (!Pattern.compile("^(holders_count)$").matcher(orderBy).matches()) {
            orderBy = "holders_count";
        }
        String ascOrDesc = sortParts.length > 1 ? sortParts[1].toLowerCase() : "desc";
        if (!"asc".equals(ascOrDesc) && !"desc".equals(ascOrDesc)) {
            ascOrDesc = "desc";
        }
        Page<NftHolderDto> nftHolderDtoPage = new Page<>(req.getPage(), req.getPageSize());
        Long lockScriptId = null;
        if (org.springframework.util.StringUtils.hasLength(req.getAddressHash())) {
            // 查找地址
            // 计算地址的哈希
            var lockScript = getAddressScript(req.getAddressHash());
            lockScriptId = lockScript.getId();
        }
        nftHolderDtoPage = dobExtendMapper.holdersPage(nftHolderDtoPage, Numeric.hexStringToByteArray(typeScriptHash), lockScriptId, orderBy, ascOrDesc);
        Set<Long> scriptIds = new HashSet<>();
        nftHolderDtoPage.getRecords().forEach(nftHolderDto -> {
            scriptIds.add(nftHolderDto.getLockScriptId());
        });
        if (scriptIds.isEmpty()) {
            return Page.of(req.getPage(), req.getPageSize(), 0);
        }
        List<Script> scripts = scriptMapper.selectByIds(scriptIds);
        nftHolderDtoPage.getRecords().forEach(nftHolderDto -> {
            Script lockScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftHolderDto.getLockScriptId())).findFirst().orElse(null);
            String addressHash = TypeConversionUtil.scriptToAddress(lockScript.getCodeHash(), lockScript.getArgs(), lockScript.getHashType());
            nftHolderDto.setAddressHash(addressHash);
        });
        Page<NftHolderResp> nftHolderRespPage = NftConvert.INSTANCE.toNftHoldersRespPage(nftHolderDtoPage);
        return nftHolderRespPage;
    }

    @Override
    public Page<NftItemResponse> nftItems(String typeScriptHash, BasePageReq req) {
        String cacheKey = String.format("%s%s:%s:page:%d:size:%d",
                ITEMS_CACHE_PREFIX, CACHE_VERSION, typeScriptHash, req.getPage(), req.getPageSize());
        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> loadNftItemsFromDatabase(typeScriptHash, req),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }

    private Page<NftItemResponse> loadNftItemsFromDatabase(String typeScriptHash, BasePageReq req) {
        CollectionsDto collectionsDto = dobExtendMapper.findDetailByScriptHash(Numeric.hexStringToByteArray(typeScriptHash));
        if (collectionsDto == null) {
            throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
        }
        Page<NftItemDto> nftItemDtoPage = new Page<>(req.getPage(), req.getPageSize());
        nftItemDtoPage = dobExtendMapper.itemsPage(nftItemDtoPage, Numeric.hexStringToByteArray(typeScriptHash));
        Set<Long> scriptIds = new HashSet<>();
        nftItemDtoPage.getRecords().forEach(nftItemDto -> {
            scriptIds.add(nftItemDto.getLockScriptId());
            scriptIds.add(nftItemDto.getTypeScriptId());
        });
        if (scriptIds.isEmpty()) {
            return Page.of(req.getPage(), req.getPageSize(), 0);
        }
        List<Script> scripts = scriptMapper.selectByIds(scriptIds);
        nftItemDtoPage.getRecords().forEach(nftItemDto -> {
            Script lockScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftItemDto.getLockScriptId())).findFirst().orElse(null);
            nftItemDto.setOwner(TypeConversionUtil.scriptToAddress(lockScript.getCodeHash(), lockScript.getArgs(), lockScript.getHashType()));
            Script typeScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftItemDto.getTypeScriptId())).findFirst().orElse(null);
            nftItemDto.setStandard(collectionsDto.getStandard());
            if(Objects.equals(NftType.M_NFT.getCode(),collectionsDto.getStandard())){
                nftItemDto.setTokenId(Numeric.toBigInt(Arrays.copyOfRange(typeScript.getArgs(),24,typeScript.getArgs().length))+"");
                nftItemDto.setIconUrl(collectionsDto.getIconUrl());
            }else {
                nftItemDto.setTokenId(Numeric.toHexString(typeScript.getArgs()));
            }
        });
        setBigDataForNftItemDto(nftItemDtoPage.getRecords());
        Page<NftItemResponse> nftItemResponsePage = NftConvert.INSTANCE.toNftItemsRespPage(nftItemDtoPage);
        return nftItemResponsePage;
    }


    @Override
    public NftItemDetailResponse itemInfo(String typeScriptHash, String tokenId) {
        String cacheKey = String.format("%s%s:typeScriptHash:%s:tokenId:%s", ITEM_INFO_CACHE_PREFIX, CACHE_VERSION, typeScriptHash,tokenId);

        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> loadNftItemInfo(typeScriptHash,tokenId),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }

    private NftItemDetailResponse loadNftItemInfo(String typeScriptHash, String tokenId){
        CollectionsDto collectionsDto = dobExtendMapper.findDetailByScriptHash(Numeric.hexStringToByteArray(typeScriptHash));
        if (collectionsDto == null) {
            throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
        }
        byte[] dobCodeArgs;
        if(Objects.equals(NftType.M_NFT.getCode(),collectionsDto.getStandard())){
            byte[] tokenByte = Numeric.toBytesPadded(new BigInteger(tokenId),4);
            dobCodeArgs = CkbUtil.concatWithByteBuffer(collectionsDto.getArgs(),tokenByte);
        }else {
            dobCodeArgs = Numeric.hexStringToByteArray(tokenId);
        }
        NftItemDto nftItemDto = dobExtendMapper.itemInfo(Numeric.hexStringToByteArray(typeScriptHash), dobCodeArgs);
        if (nftItemDto == null) {
            throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
        }
        Set<Long> scriptIds = new HashSet<>();
        scriptIds.add(nftItemDto.getLockScriptId());
        scriptIds.add(nftItemDto.getCreateLockScriptId());
        List<Script> scripts = scriptMapper.selectByIds(scriptIds);
        Script lockScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftItemDto.getLockScriptId())).findFirst().orElse(null);
        Script createlockScript = scripts.stream().filter(script -> Objects.equals(script.getId(), nftItemDto.getCreateLockScriptId())).findFirst().orElse(null);

        nftItemDto.setOwner(TypeConversionUtil.scriptToAddress(lockScript.getCodeHash(), lockScript.getArgs(), lockScript.getHashType()));
        nftItemDto.setCreator(TypeConversionUtil.scriptToAddress(createlockScript.getCodeHash(), createlockScript.getArgs(), createlockScript.getHashType()));
        nftItemDto.setTokenId(tokenId);
        nftItemDto.setStandard(collectionsDto.getStandard());
        if(Objects.equals(NftType.M_NFT.getCode(),collectionsDto.getStandard())) {
            nftItemDto.setIconUrl(collectionsDto.getIconUrl());
        }
            if (!ByteUtils.hasLength(nftItemDto.getData())) {
            List<Long> outputIds = Arrays.asList(nftItemDto.getId());
            List<OutputData> outputDataList = outputDataMapper.selectByOutputIds(outputIds);
            if (!CollectionUtils.isEmpty(outputDataList)) {
                nftItemDto.setData(outputDataList.get(0).getData());
            }
        }
        return NftConvert.INSTANCE.toNftItemDetailResp(nftItemDto);
    }


    @Override
    public List<AccountNftResponse> accountNftResponses(String address) {
        String cacheKey = String.format("%s%s:address:%s", ACCOUNT_PREFIX, CACHE_VERSION, address);

        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> loadAccountNftFromDatabase(address),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }


    private List<AccountNftResponse> loadAccountNftFromDatabase(String address) {
        var lockScript = getAddressScript(address);
        List<AccountNftDto> accountNftDtos = dobExtendMapper.accountNftInfo(lockScript.getId());
        setBigData(accountNftDtos);
        List<AccountNftResponse> accountNftResponses =  NftConvert.INSTANCE.toAccountNftResponseList(accountNftDtos);
        accountNftResponses.forEach(accountNftResponse -> {
            if(Objects.equals(accountNftResponse.getStandard(),NftType.M_NFT.getValue())){
                accountNftResponse.setNftIconFile(accountNftResponse.getIconUrl());
                String tokenId =accountNftResponse.getTokenId();
                accountNftResponse.setTokenId(Numeric.toBigInt(tokenId.substring(tokenId.length()-8,tokenId.length()))+"");
            }
        });

        return accountNftResponses;
    }


    private Script getAddressScript(String address) {
        var addressScriptHash = Address.decode(address).getScript().computeHash();
        LambdaQueryWrapper<Script> queryScriptWrapper = new LambdaQueryWrapper<>();
        queryScriptWrapper.eq(Script::getScriptHash, addressScriptHash);
        var lockScript = scriptMapper.selectOne(queryScriptWrapper);
        if (lockScript == null) {
            throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
        }
        return lockScript;
    }

    private void setBigData(List<AccountNftDto> accountNftDtos) {
        if (CollectionUtils.isEmpty(accountNftDtos)) {
            return;
        }
        List<OutputData> outputDataList;
        List<Long> outputIds = accountNftDtos.stream().filter(accountNftDto -> !ByteUtils.hasLength(accountNftDto.getData())).map(AccountNftDto::getCellId).collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(outputIds)) {
            outputDataList = outputDataMapper.selectByOutputIds(outputIds);
        } else {
            outputDataList = null;
        }
        if (!CollectionUtils.isEmpty(outputDataList)) {
            accountNftDtos.forEach(accountNftDto -> {
                if (!ByteUtils.hasLength(accountNftDto.getData())) {
                    OutputData bigData = outputDataList.stream().filter(outputData -> Objects.equals(accountNftDto.getCellId(), outputData.getOutputId())).findFirst().orElse(null);
                    if (bigData != null) {
                        accountNftDto.setData(bigData.getData());
                    }
                }
            });
        }
    }


    private void setBigDataForNftItemDto(List<NftItemDto> nftItemDtos) {
        if (CollectionUtils.isEmpty(nftItemDtos)) {
            return;
        }
        List<OutputData> outputDataList;
        List<Long> outputIds = nftItemDtos.stream().filter(nftItemDto -> !ByteUtils.hasLength(nftItemDto.getData())).map(NftItemDto::getId).collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(outputIds)) {
            outputDataList = outputDataMapper.selectByOutputIds(outputIds);
        } else {
            outputDataList = null;
        }
        if (!CollectionUtils.isEmpty(outputDataList)) {
            nftItemDtos.forEach(nftItemDto -> {
                if (!ByteUtils.hasLength(nftItemDto.getData())) {
                    OutputData bigData = outputDataList.stream().filter(outputData -> Objects.equals(nftItemDto.getId(), outputData.getOutputId())).findFirst().orElse(null);
                    if (bigData != null) {
                        nftItemDto.setData(bigData.getData());
                    }
                }
            });
        }
    }


    private void setBigDataForNftTransfer(List<NftTransfersDto> nftTransfersDtos) {
        if (CollectionUtils.isEmpty(nftTransfersDtos)) {
            return;
        }
        List<OutputData> outputDataList;
        List<Long> outputIds = nftTransfersDtos.stream().filter(nftItemDto -> !ByteUtils.hasLength(nftItemDto.getData())).map(NftTransfersDto::getId).collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(outputIds)) {
            outputDataList = outputDataMapper.selectByOutputIds(outputIds);
        } else {
            outputDataList = null;
        }
        if (!CollectionUtils.isEmpty(outputDataList)) {
            nftTransfersDtos.forEach(nftTransfersDto -> {
                if (!ByteUtils.hasLength(nftTransfersDto.getData())) {
                    OutputData bigData = outputDataList.stream().filter(outputData -> Objects.equals(nftTransfersDto.getId(), outputData.getOutputId())).findFirst().orElse(null);
                    if (bigData != null) {
                        nftTransfersDto.setData(bigData.getData());
                    }
                }
            });
        }
    }


    @Override
    public Long getStoreCellId(String tokenId){
        String cacheKey = String.format("%s%s:%s",
                STORE_CELL_PREFIX, CACHE_VERSION, tokenId);
        return cacheUtils.getCache(
                cacheKey,                    // 缓存键
                () -> getStoreCellIdByTokenId(tokenId),  // 数据加载函数
                TTL_SECONDS,                 // 缓存过期时间
                TimeUnit.SECONDS             // 时间单位
        );
    }

    private Long getStoreCellIdByTokenId(String tokenId){
         DobCode dobCode = dobCodeMapper.findByDobCodeScriptArgs(Numeric.hexStringToByteArray(tokenId));
         if(dobCode==null){
             throw new ServerException(I18nKey.TOKEN_COLLECTION_NOT_FOUND_CODE, i18n.getMessage(I18nKey.TOKEN_COLLECTION_NOT_FOUND_MESSAGE));
         }
        ScriptConfig.LockScript typeBurnLock = scriptConfig.getLockScripts().stream().filter(lockScript -> Objects.equals(lockScript.getName(), LockType.TypeBurnLock.getValue())).findFirst().orElse(null);
         if(typeBurnLock==null){
             log.error("config not found typeBurnLock");
             throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
         }
         Script typeBurnLockScript = scriptMapper.findTypeBurnLock(Numeric.hexStringToByteArray(typeBurnLock.getCodeHash()),dobCode.getDobCodeScriptId());
         if(typeBurnLockScript==null){
             throw new ServerException(I18nKey.ADDRESS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.ADDRESS_NOT_FOUND_MESSAGE));
         }
         Output output = outputMapper.findByLockScriptIdOutput(typeBurnLockScript.getId());
        if(output==null){
            throw new ServerException(I18nKey.CELL_OUTPUT_NOT_FOUND_CODE, i18n.getMessage(I18nKey.CELL_OUTPUT_NOT_FOUND_MESSAGE));
        }
         return output.getId();
    }


}
