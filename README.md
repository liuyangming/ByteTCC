ByteTCC是一个基于TCC（Try/Confirm/Cancel）机制的分布式事务管理器。兼容JTA，可以很好的与EJB、Spring等容器（本文档下文说明中将以Spring容器为例）进行集成。

## 一、快速入门
#### 1.1. 加入maven依赖
###### 1.1.1. 使用Spring Cloud
```xml
<dependency>
	<groupId>org.bytesoft</groupId>
	<artifactId>bytetcc-supports-springcloud</artifactId>
	<version>0.4.0-rc1</version>
</dependency>
```
###### 1.1.2. 使用dubbo
```xml
<dependency>
	<groupId>org.bytesoft</groupId>
	<artifactId>bytetcc-supports-dubbo</artifactId>
	<version>0.4.0-rc1</version>
</dependency>
```

#### 1.2. 编写业务服务
```java
@Service("accountService")
@Compensable(
  interfaceClass = IAccountService.class 
, confirmableKey = "accountServiceConfirm"
, cancellableKey = "accountServiceCancel"
)
public class AccountServiceImpl implements IAccountService {

	@Resource(name = "jdbcTemplate")
	private JdbcTemplate jdbcTemplate;

	@Transactional
	public void increaseAmount(String accountId, double amount) throws ServiceException {
	    this.jdbcTemplate.update("update tb_account set frozen = frozen + ? where acct_id = ?", amount, acctId);
	}

}
```

#### 1.3. 编写confirm服务
```java
@Service("accountServiceConfirm")
public class AccountServiceConfirm implements IAccountService {

	@Resource(name = "jdbcTemplate")
	private JdbcTemplate jdbcTemplate;

	@Transactional
	public void increaseAmount(String accountId, double amount) throws ServiceException {
	    this.jdbcTemplate.update("update tb_account set amount = amount + ?, frozen = frozen - ? where acct_id = ?", amount, amount, acctId);
	}

}
```

#### 1.4. 编写cancel服务
```java
@Service("accountServiceCancel")
public class AccountServiceCancel implements IAccountService {

	@Resource(name = "jdbcTemplate")
	private JdbcTemplate jdbcTemplate;

	@Transactional
	public void increaseAmount(String accountId, double amount) throws ServiceException {
	    this.jdbcTemplate.update("update tb_account set frozen = frozen - ? where acct_id = ?", amount, acctId);
	}

}
```

## 二、文档 & 样例
* 使用文档： https://github.com/liuyangming/ByteTCC/wiki
* 使用样例： https://github.com/liuyangming/ByteTCC-sample


## 三、ByteTCC特性
* 1、支持Spring容器的声明式事务管理；
* 2、支持普通事务、TCC事务、业务补偿型事务等事务机制；
* 3、支持多数据源、跨应用、跨服务器等分布式事务场景；
* 4、支持长事务；
* 5、支持dubbo服务框架；
* 6、支持spring cloud；

## 四、服务质量
#### 4.1. 故障恢复
* **任意时刻**因**任意故障**（包括但不限于：业务系统/RDBS服务器宕机；网络故障；断电等）造成的事务中断，ByteTCC均有相应机制予以恢复，保证全局事务的最终一致性。

#### 4.2. 幂等性
* ByteTCC**在框架层面提供对Confirm/Cancel业务逻辑的幂等性保障**，业务系统仅需关注自身业务本身，无需为幂等性问题而烦恼。

## 五、历史版本
#### 5.1. v0.4.x
* 地址：https://github.com/liuyangming/ByteTCC/tree/0.4.x
* 文档：https://github.com/liuyangming/ByteTCC/wiki

#### 5.2. v0.3.x
* 地址：https://github.com/liuyangming/ByteTCC/tree/0.3.x
* 文档：https://github.com/liuyangming/ByteTCC/wiki

#### 5.3. v0.2.0-alpha
* 地址：http://code.taobao.org/p/openjtcc
* 文档：http://code.taobao.org/p/openjtcc/wiki/index/

#### 5.4. v0.1.2
* 地址：http://code.google.com/p/bytetcc

#### 5.5. v0.1
* 地址：http://pan.baidu.com/s/1hq3ffxU

## 六、建议及改进
若您有任何建议，可以通过1）加入qq群537445956/606453172向群主提出，或2）发送邮件至bytefox@126.com向我反馈。本人承诺，任何建议都将会被认真考虑，优秀的建议将会被采用，但不保证一定会在当前版本中实现。
