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

import javax.persistence.EntityTransaction;

/**
 * Dummy class. Will be implemented using Zookeeper.
 *
 * @author animesh.kumar
 */
public class EntityTransactionImpl implements EntityTransaction
{

    /* @see javax.persistence.EntityTransaction#begin() */
    public void begin()
    {

    }

    /* @see javax.persistence.EntityTransaction#commit() */
    public void commit()
    {

    }

    /* @see javax.persistence.EntityTransaction#rollback() */
    public void rollback()
    {

    }

    /* @see javax.persistence.EntityTransaction#setRollbackOnly() */
    public void setRollbackOnly()
    {

    }

    /* @see javax.persistence.EntityTransaction#getRollbackOnly() */
    public boolean getRollbackOnly()
    {
        return false;
    }

    /* @see javax.persistence.EntityTransaction#isActive() */
    public boolean isActive()
    {
        return false;
    }
}
