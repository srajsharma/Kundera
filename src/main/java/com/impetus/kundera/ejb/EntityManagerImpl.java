/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.ejb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.persistence.CascadeType;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceException;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;
import javax.persistence.PrePersist;
import javax.persistence.PreRemove;
import javax.persistence.PreUpdate;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.metamodel.Metamodel;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.kundera.Client;
import com.impetus.kundera.db.DataManager;
import com.impetus.kundera.ejb.event.EntityEventDispatcher;
import com.impetus.kundera.index.IndexManager;
import com.impetus.kundera.loader.DBType;
import com.impetus.kundera.metadata.EntityMetadata;
import com.impetus.kundera.metadata.MetadataManager;
import com.impetus.kundera.mongodb.query.MongoDBQuery;
import com.impetus.kundera.proxy.EnhancedEntity;
import com.impetus.kundera.query.LuceneQuery;

/**
 * The Class EntityManagerImpl.
 *
 * @author animesh.kumar
 */
public class EntityManagerImpl implements KunderaEntityManager
{

    /** The Constant log. */
    private static final Log log = LogFactory.getLog(EntityManagerImpl.class);

    /** The factory. */
    private EntityManagerFactoryImpl factory;

    /** The closed. */
    private boolean closed = false;

    /** The client. */
    private Client client;

    /** The data manager. */
    private DataManager dataManager;

    /** The index manager. */
    private IndexManager indexManager;

    /** The metadata manager. */
    private MetadataManager metadataManager;

    /** The persistence unit name. */
    private String persistenceUnitName;

    /** The entity resolver. */
    private EntityResolver entityResolver;

    /** The session. */
    private EntityManagerSession session;

    /** The event dispatcher. */
    private EntityEventDispatcher eventDispatcher;

    /**
     * Instantiates a new entity manager impl.
     *
     * @param factory
     *            the factory
     * @param client
     *            the client
     */
    public EntityManagerImpl(EntityManagerFactoryImpl factory)
    {
        this.factory = factory;
        this.metadataManager = factory.getMetadataManager();
        this.persistenceUnitName = factory.getPersistenceUnitName();
        dataManager = new DataManager(this);
        entityResolver = new EntityResolver(this);
        session = new EntityManagerSession(this);
        eventDispatcher = new EntityEventDispatcher();
    }

    /**
     * Gets the factory.
     *
     * @return the factory
     */
    public EntityManagerFactoryImpl getFactory()
    {
        return factory;
    }

    /*
     * @see javax.persistence.EntityManager#find(java.lang.Class,
     * java.lang.Object)
     */
    @Override
    public final <E> E find(Class<E> entityClass, Object primaryKey)
    {
        if (closed)
        {
            throw new PersistenceException("EntityManager already closed.");
        }
        if (primaryKey == null)
        {
            throw new IllegalArgumentException("primaryKey value must not be null.");
        }

        // Validate
        metadataManager.validate(entityClass);

        E e = null;
        e = session.lookup(entityClass, primaryKey);
        if (null != e)
        {
            log.debug(entityClass.getName() + "_" + primaryKey + " is loaded from cache!");
            return e;
        }

        return immediateLoadAndCache(entityClass, primaryKey);
    }

    /**
     * Immediate load and cache.
     *
     * @param <E>
     *            the element type
     * @param entityClass
     *            the entity class
     * @param primaryKey
     *            the primary key
     * @return the e
     */
    protected <E> E immediateLoadAndCache(Class<E> entityClass, Object primaryKey)
    {
        try
        {
            EntityMetadata m = metadataManager.getEntityMetadata(entityClass);
            m.setDBType(this.client.getType());
            E e = dataManager.find(entityClass, m, primaryKey.toString());
            if (e != null)
            {
                session.store(primaryKey, e, m.isCacheable());
            }
            return e;
        }
        catch (Exception exp)
        {
            throw new PersistenceException(exp);
        }
    }

    /*
     * @see com.impetus.kundera.CassandraEntityManager#find(java.lang.Class,
     * java.lang.Object[])
     */
    @Override
    public final <E> List<E> find(Class<E> entityClass, Object... primaryKeys)
    {
        if (closed)
        {
            throw new PersistenceException("EntityManager already closed.");
        }
        if (primaryKeys == null)
        {
            throw new IllegalArgumentException("primaryKey value must not be null.");
        }

        // Validate
        metadataManager.validate(entityClass);

        if (null == primaryKeys || primaryKeys.length == 0)
        {
            return new ArrayList<E>();
        }

        // TODO: load from cache first

        try
        {
            String[] ids = Arrays.asList(primaryKeys).toArray(new String[] {});
            EntityMetadata m = metadataManager.getEntityMetadata(entityClass);
            m.setDBType(this.client.getType());
            List<E> entities = dataManager.find(entityClass, m, ids);

            // TODO: cache entities for future lookup
            return entities;
        }
        catch (Exception e)
        {
            throw new PersistenceException(e);
        }
    }

    /* @see javax.persistence.EntityManager#remove(java.lang.Object) */
    @Override
    public final void remove(Object e)
    {
        if (e == null)
        {
            throw new IllegalArgumentException("Entity must not be null.");
        }

        // Validate
        metadataManager.validate(e.getClass());

        try
        {

            List<EnhancedEntity> reachableEntities = entityResolver.resolve(e, CascadeType.REMOVE, this.client
                    .getType());

            // remove each one
            for (EnhancedEntity o : reachableEntities)
            {
                log.debug("Removing @Entity >> " + o);

                EntityMetadata m = metadataManager.getEntityMetadata(o.getEntity().getClass());
                m.setDBType(this.client.getType());
                // fire PreRemove events
                eventDispatcher.fireEventListeners(m, o, PreRemove.class);

                session.remove(o.getEntity().getClass(), o.getId());
                dataManager.remove(o, m);
                getIndexManager().remove(m, o.getEntity(), o.getId());

                // fire PostRemove events
                eventDispatcher.fireEventListeners(m, o, PostRemove.class);
            }
        }
        catch (Exception exp)
        {
            throw new PersistenceException(exp);
        }
    }

    /* @see javax.persistence.EntityManager#merge(java.lang.Object) */
    @Override
    public final <E> E merge(E e)
    {
        if (e == null)
        {
            throw new IllegalArgumentException("Entity must not be null.");
        }

        // Validate
        metadataManager.validate(e.getClass());

        try
        {

            List<EnhancedEntity> reachableEntities = entityResolver
                    .resolve(e, CascadeType.MERGE, this.client.getType());

            // save each one
            for (EnhancedEntity o : reachableEntities)
            {
                log.debug("Merging @Entity >> " + o);

                EntityMetadata metadata = metadataManager.getEntityMetadata(o.getEntity().getClass());
                metadata.setDBType(this.client.getType());
                // TODO: throw OptisticLockException if wrong version and
                // optimistic locking enabled

                // fire PreUpdate events
                eventDispatcher.fireEventListeners(metadata, o, PreUpdate.class);

                dataManager.merge(o, metadata);
                getIndexManager().update(metadata, o.getEntity());

                // fire PreUpdate events
                eventDispatcher.fireEventListeners(metadata, o, PostUpdate.class);
            }
        }
        catch (Exception exp)
        {
            throw new PersistenceException(exp);
        }

        return e;
    }

    /* @see javax.persistence.EntityManager#persist(java.lang.Object) */
    @Override
    public final void persist(Object e)
    {
        if (e == null)
        {
            throw new IllegalArgumentException("Entity must not be null.");
        }

        try
        {
            // validate
            metadataManager.validate(e.getClass());

            List<EnhancedEntity> reachableEntities = entityResolver.resolve(e, CascadeType.PERSIST, this.client
                    .getType());

            // save each one
            for (EnhancedEntity o : reachableEntities)
            {
                log.debug("Persisting @Entity >> " + o);

                EntityMetadata metadata = metadataManager.getEntityMetadata(o.getEntity().getClass());
                metadata.setDBType(this.client.getType());
                // TODO: throw EntityExistsException if already exists

                // fire pre-persist events
                eventDispatcher.fireEventListeners(metadata, o, PrePersist.class);

                // TODO uncomment
                dataManager.persist(o, metadata);
                getIndexManager().write(metadata, o.getEntity());

                // fire post-persist events
                eventDispatcher.fireEventListeners(metadata, o, PostPersist.class);
            }
        }
        catch (Exception exp)
        {
            exp.printStackTrace();
            throw new PersistenceException(exp);
        }
    }

    /* @see javax.persistence.EntityManager#clear() */
    @Override
    public final void clear()
    {
        checkClosed();
        session.clear();
        client.shutdown();
    }

    /* @see javax.persistence.EntityManager#close() */
    @Override
    public final void close()
    {
        closed = true;
        session = null;
    }

    /* @see javax.persistence.EntityManager#contains(java.lang.Object) */
    @Override
    public final boolean contains(Object entity)
    {
        return false;
    }

    /* @see javax.persistence.EntityManager#createNamedQuery(java.lang.String) */
    @Override
    public final Query createNamedQuery(String name)
    {
        throw new NotImplementedException("TODO");
    }

    /* @see javax.persistence.EntityManager#createNativeQuery(java.lang.String) */
    @Override
    public final Query createNativeQuery(String sqlString)
    {
        throw new NotImplementedException("TODO");
    }

    /*
     * @see javax.persistence.EntityManager#createNativeQuery(java.lang.String,
     * java.lang.Class)
     */
    @Override
    public final Query createNativeQuery(String sqlString, Class resultClass)
    {
        throw new NotImplementedException("TODO");
    }

    /*
     * @see javax.persistence.EntityManager#createNativeQuery(java.lang.String,
     * java.lang.String)
     */
    @Override
    public final Query createNativeQuery(String sqlString, String resultSetMapping)
    {
        throw new NotImplementedException("TODO");
    }

    /* @see javax.persistence.EntityManager#createQuery(java.lang.String) */
    @Override
    public final Query createQuery(String ejbqlString)
    {
        if (this.client.getType().equals(DBType.MONGODB))
        {
            return new MongoDBQuery(this, metadataManager, ejbqlString);
        }
        else
        {
            return new LuceneQuery(this, metadataManager, ejbqlString);
        }
    }

    /* @see javax.persistence.EntityManager#flush() */
    @Override
    public final void flush()
    {
        // always flushed to cassandra anyway! relax.
    }

    /* @see javax.persistence.EntityManager#getDelegate() */
    @Override
    public final Object getDelegate()
    {
        return null;
    }

    /* @see javax.persistence.EntityManager#getFlushMode() */
    @Override
    public final FlushModeType getFlushMode()
    {
        throw new NotImplementedException("TODO");
    }

    /*
     * @see javax.persistence.EntityManager#getReference(java.lang.Class,
     * java.lang.Object)
     */
    @Override
    public final <T> T getReference(Class<T> entityClass, Object primaryKey)
    {
        throw new NotImplementedException("TODO");
    }

    /* @see javax.persistence.EntityManager#getTransaction() */
    @Override
    public final EntityTransaction getTransaction()
    {
        return new EntityTransactionImpl();
    }

    /* @see javax.persistence.EntityManager#isOpen() */
    @Override
    public final boolean isOpen()
    {
        return !closed;
    }

    /* @see javax.persistence.EntityManager#joinTransaction() */
    @Override
    public final void joinTransaction()
    {
    }

    /*
     * @see javax.persistence.EntityManager#lock(java.lang.Object,
     * javax.persistence.LockModeType)
     */
    @Override
    public final void lock(Object entity, LockModeType lockMode)
    {
        throw new NotImplementedException("TODO");
    }

    /* @see javax.persistence.EntityManager#refresh(java.lang.Object) */
    @Override
    public final void refresh(Object entity)
    {
        throw new NotImplementedException("TODO");
    }   
    

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#find(java.lang.Class, java.lang.Object, java.util.Map)
     */
    @Override
    public <T> T find(Class<T> paramClass, Object paramObject, Map<String, Object> paramMap)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#find(java.lang.Class, java.lang.Object, javax.persistence.LockModeType)
     */
    @Override
    public <T> T find(Class<T> paramClass, Object paramObject, LockModeType paramLockModeType)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#find(java.lang.Class, java.lang.Object, javax.persistence.LockModeType, java.util.Map)
     */
    @Override
    public <T> T find(Class<T> paramClass, Object paramObject, LockModeType paramLockModeType,
            Map<String, Object> paramMap)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#lock(java.lang.Object, javax.persistence.LockModeType, java.util.Map)
     */
    @Override
    public void lock(Object paramObject, LockModeType paramLockModeType, Map<String, Object> paramMap)
    {
        throw new NotImplementedException("TODO");        
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#refresh(java.lang.Object, java.util.Map)
     */
    @Override
    public void refresh(Object paramObject, Map<String, Object> paramMap)
    {
        throw new NotImplementedException("TODO");
        
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#refresh(java.lang.Object, javax.persistence.LockModeType)
     */
    @Override
    public void refresh(Object paramObject, LockModeType paramLockModeType)
    {
        throw new NotImplementedException("TODO");
        
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#refresh(java.lang.Object, javax.persistence.LockModeType, java.util.Map)
     */
    @Override
    public void refresh(Object paramObject, LockModeType paramLockModeType, Map<String, Object> paramMap)
    {
        throw new NotImplementedException("TODO");        
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#detach(java.lang.Object)
     */
    @Override
    public void detach(Object paramObject)
    {
        throw new NotImplementedException("TODO");
        
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#getLockMode(java.lang.Object)
     */
    @Override
    public LockModeType getLockMode(Object paramObject)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#setProperty(java.lang.String, java.lang.Object)
     */
    @Override
    public void setProperty(String paramString, Object paramObject)
    {
        throw new NotImplementedException("TODO");
        
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#getProperties()
     */
    @Override
    public Map<String, Object> getProperties()
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#createQuery(javax.persistence.criteria.CriteriaQuery)
     */
    @Override
    public <T> TypedQuery<T> createQuery(CriteriaQuery<T> paramCriteriaQuery)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#createQuery(java.lang.String, java.lang.Class)
     */
    @Override
    public <T> TypedQuery<T> createQuery(String paramString, Class<T> paramClass)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#createNamedQuery(java.lang.String, java.lang.Class)
     */
    @Override
    public <T> TypedQuery<T> createNamedQuery(String paramString, Class<T> paramClass)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#unwrap(java.lang.Class)
     */
    @Override
    public <T> T unwrap(Class<T> paramClass)
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#getEntityManagerFactory()
     */
    @Override
    public EntityManagerFactory getEntityManagerFactory()
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#getCriteriaBuilder()
     */
    @Override
    public CriteriaBuilder getCriteriaBuilder()
    {
        throw new NotImplementedException("TODO");
    }

    /* (non-Javadoc)
     * @see javax.persistence.EntityManager#getMetamodel()
     */
    @Override
    public Metamodel getMetamodel()
    {
        throw new NotImplementedException("TODO");
    }


    /*
     * @see
     * javax.persistence.EntityManager#setFlushMode(javax.persistence.FlushModeType
     * )
     */
    @Override
    public final void setFlushMode(FlushModeType flushMode)
    {
        throw new NotImplementedException("TODO");
    }

    /**
     * Check closed.
     */
    private void checkClosed()
    {
        if (!isOpen())
        {
            throw new IllegalStateException("EntityManager has been closed.");
        }
    }

    /**
     * Gets the metadata manager.
     *
     * @return the metadataManager
     */
    public final MetadataManager getMetadataManager()
    {
        return metadataManager;
    }

    /**
     * Gets the data manager.
     *
     * @return the dataManager
     */
    public final DataManager getDataManager()
    {
        return dataManager;
    }

    /**
     * Gets the index manager.
     *
     * @return the indexManager
     */
    public final IndexManager getIndexManager()
    {
        if (indexManager == null)
        {
            indexManager = new IndexManager(this);
        }
        return indexManager;
    }

    /**
     * Gets the client.
     *
     * @return the client
     */
    @Override
    public final Client getClient()
    {
        return client;
    }

    /**
     * Gets the persistence unit name.
     *
     * @return the persistence unit name
     */
    public final String getPersistenceUnitName()
    {
        return persistenceUnitName;
    }

    /**
     * Gets the session.
     *
     * @return the session
     */
    protected EntityManagerSession getSession()
    {
        return session;
    }

    /**
     * Gets the entity resolver.
     *
     * @return the reachabilityResolver
     */
    public EntityResolver getEntityResolver()
    {
        return entityResolver;
    }

    @Override
    public <T> List<T> find(Class<T> entityClass, Map<String, String> primaryKeys)
    {

        if (closed)
        {
            throw new PersistenceException("EntityManager already closed.");
        }
        if (primaryKeys == null)
        {
            throw new IllegalArgumentException("primaryKey value must not be null.");
        }

        // Validate
        metadataManager.validate(entityClass);

        if (null == primaryKeys || primaryKeys.isEmpty())
        {
            return new ArrayList<T>();
        }

        // TODO: load from cache first

        try
        {
            // String[] ids = Arrays.asList(primaryKeys).toArray(new String[]
            // {});
            EntityMetadata m = metadataManager.getEntityMetadata(entityClass);
            m.setDBType(this.client.getType());
            List<T> entities = dataManager.find(entityClass, m, primaryKeys);

            // TODO: cache entities for future lookup
            return entities;
        }
        catch (Exception e)
        {
            throw new PersistenceException(e);
        }
    }

}
