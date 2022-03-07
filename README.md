
**ByteTCC** is an implementation of Distributed Transaction Manager, based on Try-Confirm-Cancel (TCC) mechanism. 

**ByteTCC** is comptible with JTA and could be seamlessly integrated with Spring and other Java containers.


## 1. Quick Start

#### 1.1 Add maven depenency
###### 1.1.1. Spring Cloud
```xml
<dependency>
	<groupId>org.bytesoft</groupId>
	<artifactId>bytetcc-supports-springcloud</artifactId>
	<version>0.5.12</version>
</dependency>
```
###### 1.1.2. dubbo
```xml
<dependency>
	<groupId>org.bytesoft</groupId>
	<artifactId>bytetcc-supports-dubbo</artifactId>
	<version>0.5.12</version>
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
* support declarative transaction management
* support normal transaction, TCC transaction, compensating service transaction
* support distributed transaction scenarios. e.g. multi-datasource, cross-applications and cross-servers transaction
* support long live transaction
* support Dubbo framework
* support Spring Cloud
* provide solutions for service idempotence in framework layer


## 4. Contact Me
If you have any questions or comments regarding this project, please feel free to contact me at:

1. send mail to _[bytefox#126.com](bytefox@126.com)_
~OR~
2. add Tecent QQ group 537445956/606453172/383515467

We will review all the suggestions and implement good ones in future release.
