package com.ckb.explorer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.repository.AbstractRepository;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ckb.explorer.config.ScriptConfig;
import com.ckb.explorer.config.ServerException;
import com.ckb.explorer.constants.I18nKey;
import com.ckb.explorer.domain.dto.UdtAddressCountDto;
import com.ckb.explorer.domain.req.UdtPageReq;
import com.ckb.explorer.domain.resp.UdtDetailResponse;
import com.ckb.explorer.domain.resp.UdtHolderAllocationsResponse;
import com.ckb.explorer.domain.resp.UdtsListResponse;
import com.ckb.explorer.entity.Script;
import com.ckb.explorer.entity.UdtHolderAllocations;
import com.ckb.explorer.enums.CellType;
import com.ckb.explorer.enums.LockType;
import com.ckb.explorer.mapper.Address24hTransactionMapper;
import com.ckb.explorer.mapper.UdtAccountsMapper;
import com.ckb.explorer.mapstruct.TypeScriptConvert;
import com.ckb.explorer.mapstruct.UdtHolderAllocationsConvert;
import com.ckb.explorer.service.ScriptService;
import com.ckb.explorer.service.UdtHolderAllocationsService;
import com.ckb.explorer.mapper.UdtHolderAllocationsMapper;
import com.ckb.explorer.util.I18n;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nervos.ckb.utils.Numeric;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author dell
 * @description 针对表【udt_holder_allocations】的数据库操作Service实现
 * @createDate 2025-09-08 17:09:56
 */
@Service
@Slf4j
public class UdtHolderAllocationsServiceImpl extends ServiceImpl<UdtHolderAllocationsMapper, UdtHolderAllocations>
        implements UdtHolderAllocationsService {

    @Resource
    ScriptService scriptService;

    @Resource
    ScriptConfig lockScriptConfig;

    @Resource
    UdtAccountsMapper udtAccountsMapper;

    @Resource
    Address24hTransactionMapper address24hTransactionMapper;

    @Resource
    private I18n i18n;

    @Override
    public List<UdtHolderAllocationsResponse> findByTypeScriptHash(String typeScriptHash) {
        Script script = scriptService.findByScriptHash(typeScriptHash);
        if (script == null) {
            throw new ServerException(I18nKey.UDTS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.UDTS_NOT_FOUND_MESSAGE));
        }
        QueryWrapper<UdtHolderAllocations> udtHolderAllocationsQueryWrapper = new QueryWrapper<>();
        udtHolderAllocationsQueryWrapper.eq("type_script_id", script.getId());
        List<UdtHolderAllocations> udtHolderAllocations = baseMapper.selectList(udtHolderAllocationsQueryWrapper);
        List<UdtHolderAllocationsResponse> udtHolderAllocationsResponses = UdtHolderAllocationsConvert.INSTANCE.udtHolderListtoResponse(udtHolderAllocations);
        udtHolderAllocationsResponses.forEach(udtHolderAllocationsResponse -> {
            udtHolderAllocationsResponse.setName(LockType.getValueByCode(udtHolderAllocationsResponse.getLockType()));
        });
        return udtHolderAllocationsResponses;
    }


    @Override
    public Page<UdtsListResponse> udtListStatistic(UdtPageReq req) {

        req.setSort(StringUtils.defaultIfBlank(req.getSort(), "typeScriptId.desc"));
        String[] sortParts = req.getSort().split("\\.", 2);
        String orderBy = sortParts[0];
        if (!Pattern.compile("^(typeScriptId|addressesCount|h24CkbTransactionsCount)$").matcher(orderBy).matches()) {
            orderBy = "h24CkbTransactionsCount";
        }
        String ascOrDesc = sortParts.length > 1 ? sortParts[1].toLowerCase() : "desc";
        if (!"asc".equals(ascOrDesc) && !"desc".equals(ascOrDesc)) {
            ascOrDesc = "desc";
        }
        Page<UdtAddressCountDto> page = new Page<>(req.getPage(), req.getPageSize());

        Page<UdtAddressCountDto> addressesCounts = super.baseMapper.getAddressNum(page,orderBy,ascOrDesc);
        if(CollectionUtils.isEmpty(addressesCounts.getRecords())){
            return new Page<>(req.getPage(), req.getPageSize());
        }

        List<Long> typeScriptIds = addressesCounts.getRecords().stream().map(UdtAddressCountDto::getTypeScriptId).collect(Collectors.toList());
        List<Script> scripts = scriptService.listByIds(typeScriptIds);

        addressesCounts.getRecords().stream().forEach(udtAddressCountDto -> {
            Script typeScript = scripts.stream().filter(script -> Objects.equals(script.getId(),udtAddressCountDto.getTypeScriptId())).findFirst().orElse(null);
            udtAddressCountDto.setTypeScriptHash(Numeric.toHexString(typeScript.getScriptHash()));
        });
        Page<UdtsListResponse> udtListPage = UdtHolderAllocationsConvert.INSTANCE.toUdtPage(addressesCounts);
        return udtListPage;
    }

    @Override
    public UdtDetailResponse findDetailByTypeHash(String typeHash) {
        Script script = scriptService.findByScriptHash(typeHash);
        if (script == null) {
            throw new ServerException(I18nKey.UDTS_NOT_FOUND_CODE, i18n.getMessage(I18nKey.UDTS_NOT_FOUND_MESSAGE));
        }
        Long holdersCount = super.baseMapper.selectHolderCountByTypeScriptId(script.getId());
        holdersCount = holdersCount == null ? 0 : holdersCount;
        UdtDetailResponse udtDetailResponse = new UdtDetailResponse();
        udtDetailResponse.setHoldersCount(holdersCount);
        BigInteger totalAmount = udtAccountsMapper.getTotalAmountByTypeScriptId(script.getId());
        totalAmount = totalAmount == null ? BigInteger.ZERO : totalAmount;
        udtDetailResponse.setTotalAmount(totalAmount);
        var typeScriptResponse = TypeScriptConvert.INSTANCE.toConvert(script);
        udtDetailResponse.setTypeScriptResponse(typeScriptResponse);
        return udtDetailResponse;
    }
}




