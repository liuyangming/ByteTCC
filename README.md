ByteTCC是一个基于TCC（Try/Confirm/Cancel）机制的分布式事务管理器。兼容JTA，可以很好的与EJB、Spring等容器（本文档下文说明中将以Spring容器为例）进行集成。

## 一、快速入门
#### 1.1. 加入maven依赖
```xml
<dependency>
	<groupId>org.bytesoft</groupId>
	<artifactId>bytetcc-supports</artifactId>
	<version>0.3.0-RC3</version>
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

## 四、历史版本
#### 4.1. v0.1.2
* 地址：http://code.google.com/p/bytetcc

#### 4.2. v0.2.0-alpha
* 地址：http://code.taobao.org/p/openjtcc
* 文档：http://code.taobao.org/p/openjtcc/wiki/index/

## 五、建议及改进
若您有任何建议，可以通过1）加入qq群537445956向群主提出，或2）发送邮件至bytefox@126.com向我反馈。本人承诺，任何建议都将会被认真考虑，优秀的建议将会被采用，但不保证一定会在当前版本中实现。
