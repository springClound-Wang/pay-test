package com.wish.pay.web.controller;

import com.alibaba.fastjson.JSON;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.PreOrderResult;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.wish.pay.ali.common.AliPayConstants;
import com.wish.pay.ali.common.SceneEnum;
import com.wish.pay.ali.service.AliPayServiceImpl;
import com.wish.pay.common.model.*;
import com.wish.pay.common.model.result.RefundResult;
import com.wish.pay.common.model.result.TradeQueryResult;
import com.wish.pay.common.model.result.TradeResult;
import com.wish.pay.common.utils.BeanMapper;
import com.wish.pay.common.utils.JsonUtils;
import com.wish.pay.common.utils.TradeStatusEnum;
import com.wish.pay.common.utils.ZxingUtils;
import com.wish.pay.common.utils.validator.ValidationResult;
import com.wish.pay.common.utils.validator.ValidationUtils;
import com.wish.pay.web.bean.*;
import com.wish.pay.web.dao.entity.TradeOrder;
import com.wish.pay.web.exception.BusinessException;
import com.wish.pay.web.service.ITradeOrderService;
import com.wish.pay.wx.model.common.ProtocolResData;
import com.wish.pay.wx.service.WxPayServiceImpl;
/*import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;*/
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.*;


@Controller
@RequestMapping("/pay")
public class PayController {

    Logger logger = LoggerFactory.getLogger(PayController.class);
    @Autowired
    ITradeOrderService tradeOrderService;

    @Autowired
    AliPayServiceImpl aliPayService;
    @Autowired
    WxPayServiceImpl wxPayService;

    @Value("${aliPay.notifyResultUrl}")
    String ALiPayNotifyResultUrl;
    @Value("${wxPay.notifyResultUrl}")
    String WXPayNotifyResultUrl;
    @Value("${aliPay.alipayPublicKey}")
    String AliPayPublicKey;

    @PostMapping("/test1")
    public  ModelAndView test1(CreatePayBean createPay,HttpServletResponse response) throws Exception{
        TradeOrder order = new TradeOrder();
        BeanMapper.copy(createPay, order);
        //2.插入订单记录
        boolean insertResult = tradeOrderService.save(order);
        //3.调用支付宝，创建订单，返回参数（订单支付url）
        if (insertResult) {
            TradePrecreate tradePrecreate = new TradePrecreate();
            tradePrecreate.setOutTradeNo(order.getOrderSerial());
            tradePrecreate.setScene(SceneEnum.BarCode.getType());
            tradePrecreate.setSubject("购买" + order.getGoodsName());
            tradePrecreate.setStoreId("test_001");
            tradePrecreate.setTotalAmount(order.getAmount().toString());
            tradePrecreate.setTimeoutExpress("90m");
            String form =aliPayService.test1(ALiPayNotifyResultUrl,tradePrecreate);
            /*ModelAndView modelAndView=new ModelAndView("form");
            modelAndView.addObject("form",form);
            return modelAndView;*/
            response.setContentType("text/html;charset=utf-8");
            response.setCharacterEncoding("utf-8");
          Writer writer= response.getWriter();
            writer.write(form);
            writer.close();
        }
        return null;
    }

    /**
     * 创建阿里支付订单
     *
     * @param createPay
     * @return
     * @throws Exception
     */
   // @ApiOperation(value="创建阿里支付订单", notes="传递CreatePayBean要求的参数,创建支付宝订单,并且返回支付二维码")
   // @ApiImplicitParam(name = "createPay",value ="创建订单参数",required = true,dataType = "CreatePayBean")
    @RequestMapping(value = "/createPay", method = {RequestMethod.POST, RequestMethod.GET}, produces = "image/jpeg;charset=UTF-8")
    @ResponseBody
    public byte[] createPay(CreatePayBean createPay) throws Exception {
        //1.校验参数
        ValidationResult result = ValidationUtils.validateEntity(createPay);
        if (result.isHasErrors()) {
            throw new BusinessException(result.toString());
        }
        TradeOrder order = new TradeOrder();
        BeanMapper.copy(createPay, order);
        //2.插入订单记录
        boolean insertResult = tradeOrderService.save(order);
        //3.调用支付宝，创建订单，返回参数（订单支付url）
        if (insertResult) {
            TradePrecreate tradePrecreate = new TradePrecreate();
            tradePrecreate.setOutTradeNo(order.getOrderSerial());
            tradePrecreate.setScene(SceneEnum.BarCode.getType());
            tradePrecreate.setSubject("购买" + order.getGoodsName());
            tradePrecreate.setStoreId("test_001");
            tradePrecreate.setTotalAmount(order.getAmount().toString());
            tradePrecreate.setTimeoutExpress("90m");
            TradeResult tradeResult = null;
            if (createPay.getPayWay().equalsIgnoreCase(PayTypeEnum.AliPay.getType())) {
                tradeResult = aliPayService.createTradeOrder(tradePrecreate, ALiPayNotifyResultUrl);
            } else if (createPay.getPayWay().equalsIgnoreCase(PayTypeEnum.WxPay.getType())) {
                int money = new BigDecimal(tradePrecreate.getTotalAmount()).multiply(new BigDecimal(100)).intValue();
               // PreOrderResult placeOrder=  placeOrder("APP支付测试",tradePrecreate.getOutTradeNo(),money+"");
                tradeResult = wxPayService.createTradeOrder(tradePrecreate, WXPayNotifyResultUrl);
            }
            if (tradeResult != null && tradeResult.isTradeSuccess()) {//支付成功
                BufferedImage bufferedImage = ZxingUtils.writeInfoToJpgBuffImg(tradeResult.getQrCode(), 400, 400);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bufferedImage, "JPEG", baos);
                return baos.toByteArray();
            }
        }
        return null;
    }

    public PreOrderResult placeOrder(String body, String out_trade_no, String total_fee) throws Exception {
        // 生成预付单对象
        PreOrder o = new PreOrder();
        // 生成随机字符串
        String nonce_str = UUID.randomUUID().toString().trim().replaceAll("-", "");
        o.setAppid("wx398e724414a273cd");
        o.setBody(body);
        o.setMch_id("1590189441");
        o.setNotify_url("http://www.bodahuyu.cn");
        o.setOut_trade_no(out_trade_no);
        // 判断有没有输入订单总金额，没有输入默认1分钱
        if (total_fee != null && !total_fee.equals("")) {
            o.setTotal_fee(Integer.parseInt(total_fee));
        } else {
            o.setTotal_fee(1);
        }
        o.setNonce_str(nonce_str);
        o.setTrade_type("NATIVE");
        o.setSpbill_create_ip("127.0.0.1");
        SortedMap<Object, Object> p = new TreeMap<Object, Object>();
        p.put("appid", "wx398e724414a273cd");
        p.put("mch_id", "1590189441");
        p.put("body", body);
        p.put("nonce_str", nonce_str);
        p.put("out_trade_no", out_trade_no);
        p.put("total_fee", total_fee);
        p.put("spbill_create_ip","127.0.0.1");
        p.put("notify_url", "http://www.bodahuyu.cn");
        p.put("trade_type", "NATIVE");
        // 获得签名
        String sign = Sign.createSign("utf-8", p,"MIIEvQIBADANBgkqhkiG9w0BAQEFAASC");
        o.setSign(sign);
        // Object转换为XML
        String xml = XmlUtil.object2Xml(o, PreOrder.class);
        System.out.println("-===="+xml);
        // 统一下单地址
        String url = "https://api.mch.weixin.qq.com/pay/unifiedorder";
        // 调用微信统一下单地址
        String returnXml = HttpUtil.sendPost(url, xml);

        // XML转换为Object
        PreOrderResult preOrderResult = (PreOrderResult) XmlUtil.xml2Object(returnXml, PreOrderResult.class);
        System.out.println("-===="+ JSON.toJSONString(preOrderResult));
        return preOrderResult;
    }
    /**
     * 查询交易状态
     *
     * @param tradeStatus
     * @return
     * @throws Exception
     */
   // @ApiOperation(value="查询交易状态", notes="查询交易状态")
   // @ApiImplicitParam(name = "tradeStatus",value ="订单状态参数",required = true,dataType = "QueryTradeStatus")
    @RequestMapping(value = "/queryTradeOrderStatus", method = {RequestMethod.GET})
    @ResponseBody
    public TradeQueryResult queryTradeOrderStatus(QueryTradeStatus tradeStatus) throws Exception {
        ValidationResult result = ValidationUtils.validateEntity(tradeStatus);
        if (result.isHasErrors()) {
            throw new BusinessException(result.toString());
        }
        TradeQuery query = new TradeQuery();
        query.setOutTradeNo(tradeStatus.getOrderSerial());
        if (tradeStatus.getPayWay().equals("0")) {//支付宝
            return aliPayService.queryPreTradeOrder(query);
        } else if (tradeStatus.getPayWay().equals("1")) {//微信
            return wxPayService.queryPreTradeOrder(query);
        }
        return null;
    }


    /**
     * 交易退款
     *
     * @param refundTrade
     * @return
     */
   // @ApiOperation(value="交易退款", notes="交易退款")
    //@ApiImplicitParam(name = "refundTrade",value ="交易退款参数",required = true,dataType = "RefundTrade")
    @RequestMapping(value = "/refundTradeOrder", method = {RequestMethod.GET})
    @ResponseBody
    public String refundTradeOrder(RefundTrade refundTrade) throws Exception {
        ValidationResult result = ValidationUtils.validateEntity(refundTrade);
        if (result.isHasErrors()) {
            throw new BusinessException(result.toString());
        }
        TradeRefund refund = new TradeRefund();
        BeanMapper.copy(refundTrade, refund);
        TradeResult tradeResult = new TradeResult();
        if (refundTrade.getPayWay().equals("0")) {//支付宝支付宝
            tradeResult = aliPayService.refundTradeOrder(refund);
            logger.info("[refundTradeOrder][alipay]退款返回结果", tradeResult);
        } else if (refundTrade.getPayWay().equals("1")) {//微信
            tradeResult = wxPayService.refundTradeOrder(refund);
            logger.info("[refundTradeOrder][wxPay]退款返回结果", tradeResult);
        }
        return tradeResult.getMsg();
    }

    /**
     * 查询交易退款
     *
     * @param queryRefundTrade
     * @return
     */
   // @ApiOperation(value="查询交易退款", notes="查询交易退款")
    //@ApiImplicitParam(name = "queryRefundTrade",value ="查询交易退款参数",required = true,dataType = "QueryRefundTrade")
    @RequestMapping(value = "/queryRefundTradeOrder", method = {RequestMethod.GET})
    @ResponseBody
    public String queryRefundTradeOrder(QueryRefundTrade queryRefundTrade) throws Exception {
        ValidationResult result = ValidationUtils.validateEntity(queryRefundTrade);
        if (result.isHasErrors()) {
            throw new BusinessException(result.toString());
        }
        TradeRefund refund = new TradeRefund();
        BeanMapper.copy(queryRefundTrade, refund);
        RefundResult tradeResult = new RefundResult();
        if (queryRefundTrade.getPayWay().equals("0")) {//支付宝
            tradeResult = aliPayService.queryRefundTradeOrder(refund);
            logger.info("[refundTradeOrder][alipay]退款返回结果", tradeResult);
        } else if (queryRefundTrade.getPayWay().equals("1")) {//微信
            tradeResult = wxPayService.queryRefundTradeOrder(refund);
            logger.info("[refundTradeOrder][wxPay]退款返回结果", tradeResult);
        }
        return tradeResult.getMsg();
    }

    /**
     * 微信通知接口
     *
     * @param request
     * @return
     * @throws Exception
     */
   // @ApiOperation(value="微信通知接口", notes="微信通知接口")
    //@ApiImplicitParam(name = "request",value ="微信通知HttpServletRequest参数",required = true,dataType = "HttpServletRequest")
    @RequestMapping(value = "/notifyFromWx", method = {RequestMethod.POST, RequestMethod.GET})
    @ResponseBody
    public ProtocolResData notifyFromWx(HttpServletRequest request, HttpServletResponse response) throws Exception {
        logger.info("notifyFromWx begin--->", request);
        System.out.println("notifyFromWx" + request.toString());
        ProtocolResData resData = new ProtocolResData();
        try {
            Map<String, String> resultMap = aliPayService.notifyResult(request, AliPayPublicKey);
            String value = resultMap.get(Contains.WishPayResultKey);
            if (StringUtils.isNotBlank(value) && value.equalsIgnoreCase(Contains.SUCCESS)) {
                //TODO:业务逻辑处理
            /*商户需要验证该通知数据中的out_trade_no是否为商户系统中创建的订单号，并判断total_amount是否确实为该订单的实际金额（即商户订单创建时的金额），
            同时需要校验通知中的seller_id（或者seller_email) 是否为out_trade_no这笔单据的对应的操作方（有的时候，一个商户可能有多个seller_id/seller_email），
            上述有任何一个验证不通过，则表明本次通知是异常通知，务必忽略。
            在上述验证通过后商户必须根据支付宝不同类型的业务通知，正确的进行不同的业务处理，并且过滤重复的通知结果数据。
            在支付宝的业务通知中，只有交易通知状态为TRADE_SUCCESS或TRADE_FINISHED时，支付宝才会认定为买家付款成功。*/
                String out_trade_no = resultMap.get("out_trade_no");
                String total_amount = resultMap.get("total_amount");
                String trade_status = resultMap.get("trade_status");
                TradeOrder tradeOrder = tradeOrderService.getTradeOrderByOrderSerial(out_trade_no);
                System.out.println((tradeOrder.getAmount().doubleValue() + "").equals(total_amount));
                if (tradeOrder != null && (tradeOrder.getAmount().doubleValue() + "").equals(total_amount) &&
                        (trade_status.equals("TRADE_SUCCESS") || trade_status.equals("TRADE_FINISHED"))) {
                    tradeOrder.setStatus(TradeStatusEnum.PAY_SUCCESS.getValue());//交易成功
                    boolean updateResult = tradeOrderService.update(tradeOrder);
                    if (!updateResult) {
                        logger.error("流水号：" + out_trade_no + " 更新状态更新出错");
                        throw new Exception("业务出错");
                    }
                }
            }
            resData.setReturn_code("SUCCESS");
            resData.setReturn_msg("OK");
        } catch (Exception e) {
            resData.setReturn_code("Fail");
            resData.setReturn_msg("NOOK");
            resData.setReturn_msg("程序业务处理失败");
            e.printStackTrace();
            //return WxUtils.notifyFalseXml();
        }
        return resData;
    }


    /**
     * 支付宝请求此接口，是post方式，而且
     * 支付宝通知接口,如果成功则会输出success，
     * 程序执行完后必须打印输出“success”（不包含引号）否则支付宝服务器会不断重发通知，直到超过24小时22分钟。
     * 一般情况下，25小时以内完成8次通知（通知的间隔频率一般是：4m,10m,10m,1h,2h,6h,15h）；
     *
     * @param request
     * @return
     * @throws Exception
     */
    //@ApiOperation(value="阿里支付通知接口", notes="阿里支付通知接口")
    //@ApiImplicitParam(name = "request",value ="阿里支付通知HttpServletRequest参数",required = true,dataType = "HttpServletRequest")
    @RequestMapping(value = "/notifyFromAli", method = {RequestMethod.POST})
    @ResponseBody
    public String notifyFromAli(HttpServletRequest request) throws Exception {
        logger.info("notifyFromAli begin--->", request);
        System.out.println("notifyFromAli" + request.toString());
        Map<String, String> resultMap = aliPayService.notifyResult(request, AliPayPublicKey);
        String value = resultMap.get(Contains.WishPayResultKey);
        if (StringUtils.isNotBlank(value) && value.equalsIgnoreCase(Contains.SUCCESS)) {
            //TODO:业务逻辑处理
            /*商户需要验证该通知数据中的out_trade_no是否为商户系统中创建的订单号，并判断total_amount是否确实为该订单的实际金额（即商户订单创建时的金额），
            同时需要校验通知中的seller_id（或者seller_email) 是否为out_trade_no这笔单据的对应的操作方（有的时候，一个商户可能有多个seller_id/seller_email），
            上述有任何一个验证不通过，则表明本次通知是异常通知，务必忽略。
            在上述验证通过后商户必须根据支付宝不同类型的业务通知，正确的进行不同的业务处理，并且过滤重复的通知结果数据。
            在支付宝的业务通知中，只有交易通知状态为TRADE_SUCCESS或TRADE_FINISHED时，支付宝才会认定为买家付款成功。*/
            String out_trade_no = resultMap.get("out_trade_no");
            String total_amount = resultMap.get("total_amount");
            String trade_status = resultMap.get("trade_status");
            TradeOrder tradeOrder = tradeOrderService.getTradeOrderByOrderSerial(out_trade_no);
            System.out.println((tradeOrder.getAmount().doubleValue() + "").equals(total_amount));
            if (tradeOrder != null && (tradeOrder.getAmount().doubleValue() + "").equals(total_amount) &&
                    (trade_status.equals("TRADE_SUCCESS") || trade_status.equals("TRADE_FINISHED"))) {
                tradeOrder.setStatus(TradeStatusEnum.PAY_SUCCESS.getValue());//交易成功
                boolean updateResult = tradeOrderService.update(tradeOrder);
                if (!updateResult) {
                    logger.error("流水号：" + out_trade_no + " 更新状态更新出错");
                    throw new Exception("业务出错");
                }
            }
        }
        return value;
    }

}
