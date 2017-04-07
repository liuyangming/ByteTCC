
**ByteTCC** is an implementation of Distributed Transaction Manager, based on Try-Confirm-Cancel (TCC) mechanism. 

**ByteTCC** is comptible with JTA and could be seamlessly integrated with Spring and other Java containers.


## 1. Quick Start

#### 1.1 Add maven depenency
```xml
<dependency>
	<groupId>org.bytesoft</groupId>
	<artifactId>bytetcc-supports</artifactId>
	<version>0.3.1</version>
</dependency>
```

#### 1.2 Compose a business service
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


#### 1.3 Compose a confirm service
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


#### 1.4 Compose a cancel service
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


## 2. Documentation & Samples
* [Document](https://github.com/liuyangming/ByteTCC/wiki)
* [Sample](https://github.com/liuyangming/ByteTCC-sample)



## 3. Features
* 1. support declarative transaction management
* 2. support normal transaction, TCC transaction, compensating service transaction
* 3. support distributed transaction scenarios. e.g. multi-datasource, cross-applications and cross-servers transaction
* 4. support long live transaction
* 5. support Dubbo framework
* 6. provide solutions for service idempotence  in framework layer


## 4. History

#### v0.2.0-alpha
* Link：[http://code.taobao.org/p/openjtcc](http://code.taobao.org/p/openjtcc)
* Doc：[http://code.taobao.org/p/openjtcc/wiki/index/](http://code.taobao.org/p/openjtcc/wiki/index/) 

#### v0.1.2
* Link：[http://code.google.com/p/bytetcc](http://code.google.com/p/bytetcc)

#### v0.1
* Link：[http://pan.baidu.com/s/1hq3ffxU
](http://pan.baidu.com/s/1hq3ffxU)


## 5. Contact Me
If you have any questions or comements regarding this project, please feel free to contact me at:

1. send mail to _[bytefox@126.com](bytefox@126.com)_
~OR~
2. add Tecent QQ group 537445956

We will review all the suggestions and implement good ones in future release.
