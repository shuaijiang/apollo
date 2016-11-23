package com.ctrip.framework.apollo.portal.service;

import com.ctrip.framework.apollo.common.dto.GrayReleaseRuleDTO;
import com.ctrip.framework.apollo.common.dto.ItemChangeSets;
import com.ctrip.framework.apollo.common.dto.ItemDTO;
import com.ctrip.framework.apollo.common.dto.NamespaceDTO;
import com.ctrip.framework.apollo.common.dto.ReleaseDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.portal.api.AdminServiceAPI;
import com.ctrip.framework.apollo.portal.auth.PermissionValidator;
import com.ctrip.framework.apollo.portal.auth.UserInfoHolder;
import com.ctrip.framework.apollo.portal.components.ItemsComparator;
import com.ctrip.framework.apollo.portal.constant.CatEventType;
import com.ctrip.framework.apollo.portal.entity.vo.NamespaceVO;
import com.dianping.cat.Cat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class NamespaceBranchService {

  @Autowired
  private ItemsComparator itemsComparator;
  @Autowired
  private UserInfoHolder userInfoHolder;
  @Autowired
  private NamespaceService namespaceService;
  @Autowired
  private ItemService itemService;
  @Autowired
  private AdminServiceAPI.NamespaceBranchAPI namespaceBranchAPI;
  @Autowired
  private ReleaseService releaseService;
  @Autowired
  private PermissionValidator permissionValidator;


  @Transactional
  public NamespaceDTO createBranch(String appId, Env env, String parentClusterName, String namespaceName) {
    NamespaceDTO createdBranch = namespaceBranchAPI.createBranch(appId, env, parentClusterName, namespaceName,
                                                                 userInfoHolder.getUser().getUserId());

    Cat.logEvent(CatEventType.CREATE_GRAY_RELEASE, String.format("%s+%s+%s+%s", appId, env, parentClusterName,
                                                                 namespaceName));
    return createdBranch;

  }

  public GrayReleaseRuleDTO findBranchGrayRules(String appId, Env env, String clusterName,
                                                String namespaceName, String branchName) {
    return namespaceBranchAPI.findBranchGrayRules(appId, env, clusterName, namespaceName, branchName);

  }

  public void updateBranchGrayRules(String appId, Env env, String clusterName, String namespaceName,
                                    String branchName, GrayReleaseRuleDTO rules) {

    String operator = userInfoHolder.getUser().getUserId();
    rules.setDataChangeCreatedBy(operator);
    rules.setDataChangeLastModifiedBy(operator);

    namespaceBranchAPI.updateBranchGrayRules(appId, env, clusterName, namespaceName, branchName, rules);

    Cat.logEvent(CatEventType.UPDATE_GRAY_RELEASE_RULE,
                 String.format("%s+%s+%s+%s", appId, env, clusterName, namespaceName));
  }

  public void deleteBranch(String appId, Env env, String clusterName, String namespaceName,
                           String branchName) {

    String operator = userInfoHolder.getUser().getUserId();

    //Refusing request if user has not release permission and branch has been released
    if (!permissionValidator.hasReleaseNamespacePermission(appId, namespaceName)
        && (!permissionValidator.hasModifyNamespacePermission(appId, namespaceName) ||
            releaseService.loadLatestRelease(appId, env, branchName, namespaceName) != null)) {
      throw new BadRequestException("Forbidden operation. "
                                    + "Cause by: you has not release permission "
                                    + "or you has not modify permission "
                                    + "or you has modify permission but branch has been released");
    }

    namespaceBranchAPI.deleteBranch(appId, env, clusterName, namespaceName, branchName, operator);

    Cat.logEvent(CatEventType.DELETE_GRAY_RELEASE,
                 String.format("%s+%s+%s+%s", appId, env, clusterName, namespaceName));
  }


  public ReleaseDTO merge(String appId, Env env, String clusterName, String namespaceName,
                          String branchName, String title, String comment, boolean deleteBranch) {

    ItemChangeSets changeSets = calculateBranchChangeSet(appId, env, clusterName, namespaceName, branchName);

    ReleaseDTO
        mergedResult =
        releaseService.updateAndPublish(appId, env, clusterName, namespaceName, title, comment, branchName, deleteBranch, changeSets);

    Cat.logEvent(CatEventType.MERGE_GRAY_RELEASE,
                 String.format("%s+%s+%s+%s", appId, env, clusterName, namespaceName));

    return mergedResult;
  }

  private ItemChangeSets calculateBranchChangeSet(String appId, Env env, String clusterName, String namespaceName,
                                                  String branchName) {
    NamespaceVO parentNamespace = namespaceService.loadNamespace(appId, env, clusterName, namespaceName);

    if (parentNamespace == null) {
      throw new BadRequestException("base namespace not existed");
    }

    if (parentNamespace.getItemModifiedCnt() > 0) {
      throw new BadRequestException("Merge operation failed. Because master has modified items");
    }

    List<ItemDTO> masterItems = itemService.findItems(appId, env, clusterName, namespaceName);

    List<ItemDTO> branchItems = itemService.findItems(appId, env, branchName, namespaceName);

    ItemChangeSets changeSets = itemsComparator.compareIgnoreBlankAndCommentItem(parentNamespace.getBaseInfo().getId(),
                                                                                 masterItems, branchItems);
    changeSets.setDeleteItems(Collections.emptyList());
    changeSets.setDataChangeLastModifiedBy(userInfoHolder.getUser().getUserId());
    return changeSets;
  }

  public NamespaceDTO findBranchBaseInfo(String appId, Env env, String clusterName, String namespaceName) {
    return namespaceBranchAPI.findBranch(appId, env, clusterName, namespaceName);
  }

  public NamespaceVO findBranch(String appId, Env env, String clusterName, String namespaceName) {
    NamespaceDTO namespaceDTO = findBranchBaseInfo(appId, env, clusterName, namespaceName);
    if (namespaceDTO == null) {
      return null;
    }
    return namespaceService.loadNamespace(appId, env, namespaceDTO.getClusterName(), namespaceName);
  }

}