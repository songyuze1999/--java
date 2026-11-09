package com.zbkj.service.service.impl;

import com.alibaba.fastjson.JSON;
import com.zbkj.common.exception.CrmebException;
import com.zbkj.common.model.cat.StoreCart;
import com.zbkj.common.model.order.StoreOrder;
import com.zbkj.common.model.order.StoreOrderInfo;
import com.zbkj.common.model.product.StoreProduct;
import com.zbkj.common.model.product.StoreProductAttrValue;
import com.zbkj.common.model.user.User;
import com.zbkj.common.request.PreOrderDetailRequest;
import com.zbkj.common.request.PreOrderRequest;
import com.zbkj.common.response.OrderInfoResponse;
import com.zbkj.common.vo.OrderInfoDetailVo;
import com.zbkj.common.vo.OrderInfoVo;
import com.zbkj.common.vo.StoreOrderInfoVo;
import com.zbkj.service.service.StoreCartService;
import com.zbkj.service.service.StoreOrderInfoService;
import com.zbkj.service.service.StoreOrderService;
import com.zbkj.service.service.StoreProductAttrValueService;
import com.zbkj.service.service.StoreProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class FullPaymentPresaleTest {
    private OrderServiceImpl service;
    private User user;
    private Map<Integer, StoreProduct> products;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl();
        products = new HashMap<>();
        user = new User();
        user.setUid(1);
        user.setLevel(0);
        ReflectionTestUtils.setField(service, "storeProductService", stub(StoreProductService.class,
                (method, args) -> products.get(args[0])));
        ReflectionTestUtils.setField(service, "attrValueService", stub(StoreProductAttrValueService.class,
                (method, args) -> {
                    StoreProductAttrValue sku = new StoreProductAttrValue();
                    sku.setId(10);
                    sku.setIsShow(true);
                    sku.setStock(20);
                    sku.setPrice(BigDecimal.TEN);
                    return sku;
                }));
    }

    @Test
    void buyNowRetainsDeliveryPromiseAfterProductChanges() {
        StoreProduct product = product(1, true, "付款后30天内发货");
        OrderInfoDetailVo detail = preOrder("buyNow", new PreOrderDetailRequest()
                .setProductId(1).setAttrValueId(10).setProductNum(2)).getOrderDetailList().get(0);
        assertTrue(detail.getIsPresale());
        assertEquals("付款后30天内发货", detail.getPresaleDeliveryDesc());
        assertEquals(new BigDecimal("10"), detail.getPrice());
        assertEquals(2, detail.getPayNum());

        StoreOrderInfo persisted = new StoreOrderInfo();
        persisted.setInfo(JSON.toJSONString(detail));
        product.setIsPresale(false);
        product.setPresaleDeliveryDesc("");
        OrderInfoResponse response = new OrderInfoResponse();
        ReflectionTestUtils.invokeMethod(service, "copyPresaleSnapshot", persisted, response);
        assertTrue(response.getIsPresale());
        assertEquals("付款后30天内发货", response.getPresaleDeliveryDesc());
    }

    @Test
    void shoppingCartKeepsPresaleInformationPerProduct() {
        product(1, true, "预计12月15日前发货");
        product(2, false, "");
        ReflectionTestUtils.setField(service, "storeCartService", stub(StoreCartService.class,
                (method, args) -> {
                    StoreCart cart = new StoreCart();
                    cart.setProductId(((Long) args[0]).intValue());
                    cart.setProductAttrUnique("10");
                    cart.setCartNum(1);
                    return cart;
                }));
        OrderInfoVo order = preOrder("shoppingCart", new PreOrderDetailRequest().setShoppingCartId(1L),
                new PreOrderDetailRequest().setShoppingCartId(2L));
        assertTrue(order.getOrderDetailList().get(0).getIsPresale());
        assertFalse(order.getOrderDetailList().get(1).getIsPresale());
        assertEquals("", order.getOrderDetailList().get(1).getPresaleDeliveryDesc());
    }

    @Test
    void buyingAgainUsesCurrentProductInsteadOfOldPromise() {
        product(1, true, "新的发货说明");
        StoreOrder completed = new StoreOrder();
        completed.setId(1);
        completed.setStatus(3);
        completed.setRefundStatus(0);
        completed.setSeckillId(0);
        completed.setBargainId(0);
        completed.setCombinationId(0);
        completed.setType(0);
        completed.setIsDel(false);
        completed.setIsSystemDel(false);
        OrderInfoDetailVo oldDetail = new OrderInfoDetailVo();
        oldDetail.setProductId(1);
        oldDetail.setAttrValueId(10);
        oldDetail.setPayNum(1);
        oldDetail.setIsPresale(true);
        oldDetail.setPresaleDeliveryDesc("旧的发货说明");
        StoreOrderInfoVo oldInfo = new StoreOrderInfoVo();
        oldInfo.setInfo(oldDetail);
        ReflectionTestUtils.setField(service, "storeOrderService", stub(StoreOrderService.class,
                (method, args) -> completed));
        ReflectionTestUtils.setField(service, "storeOrderInfoService", stub(StoreOrderInfoService.class,
                (method, args) -> Collections.singletonList(oldInfo)));

        OrderInfoDetailVo detail = preOrder("again", new PreOrderDetailRequest().setOrderNo("old-order"))
                .getOrderDetailList().get(0);
        assertEquals("新的发货说明", detail.getPresaleDeliveryDesc());
        assertEquals("旧的发货说明", oldDetail.getPresaleDeliveryDesc());
    }

    @Test
    void historicalOrdersRemainOrdinaryEvenIfProductIsNowPresale() {
        product(1, true, "新的发货说明");
        for (String info : Arrays.asList("{\"productId\":1,\"productName\":\"历史商品\"}", "", "null")) {
            StoreOrderInfo persisted = new StoreOrderInfo();
            persisted.setInfo(info);
            OrderInfoResponse response = new OrderInfoResponse();
            ReflectionTestUtils.invokeMethod(service, "copyPresaleSnapshot", persisted, response);
            assertFalse(response.getIsPresale());
            assertEquals("", response.getPresaleDeliveryDesc());
        }
    }

    @Test
    void changedPromiseRequiresCustomerToConfirmAgain() {
        StoreProduct product = product(1, true, "原发货说明");
        OrderInfoDetailVo detail = preOrder("buyNow", new PreOrderDetailRequest()
                .setProductId(1).setAttrValueId(10).setProductNum(1)).getOrderDetailList().get(0);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "validatePresaleSnapshot", product, detail));
        product.setPresaleDeliveryDesc("延后的发货说明");
        assertThrows(CrmebException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "validatePresaleSnapshot", product, detail));
        product.setIsPresale(false);
        assertThrows(CrmebException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "validatePresaleSnapshot", product, detail));
    }

    @Test
    void presaleRequiresValidDescriptionAndSpotGoodsClearIt() {
        StoreProductServiceImpl productService = new StoreProductServiceImpl();
        StoreProduct product = product(1, true, "  ");
        assertThrows(CrmebException.class, () -> ReflectionTestUtils.invokeMethod(
                productService, "normalizePresaleConfig", product));
        product.setPresaleDeliveryDesc(String.join("", Collections.nCopies(256, "字")));
        assertThrows(CrmebException.class, () -> ReflectionTestUtils.invokeMethod(
                productService, "normalizePresaleConfig", product));
        product.setPresaleDeliveryDesc("  付款后30天内发货  ");
        ReflectionTestUtils.invokeMethod(productService, "normalizePresaleConfig", product);
        assertEquals("付款后30天内发货", product.getPresaleDeliveryDesc());
        product.setIsPresale(false);
        ReflectionTestUtils.invokeMethod(productService, "normalizePresaleConfig", product);
        assertEquals("", product.getPresaleDeliveryDesc());
    }

    private StoreProduct product(int id, boolean presale, String description) {
        StoreProduct product = new StoreProduct();
        product.setId(id);
        product.setStoreName("商品" + id);
        product.setStock(20);
        product.setIsDel(false);
        product.setIsShow(true);
        product.setIsPresale(presale);
        product.setPresaleDeliveryDesc(description);
        products.put(id, product);
        return product;
    }

    private OrderInfoVo preOrder(String type, PreOrderDetailRequest... details) {
        PreOrderRequest request = new PreOrderRequest();
        request.setPreOrderType(type);
        request.setOrderDetails(Arrays.asList(details));
        return ReflectionTestUtils.invokeMethod(service, "validatePreOrderRequest", request, user);
    }

    private <T> T stub(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return type.getSimpleName() + "Stub";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                    }
                    return handler.apply(method.getName(), args);
                }));
    }
}
