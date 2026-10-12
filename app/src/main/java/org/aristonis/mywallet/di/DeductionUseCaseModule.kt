package org.aristonis.mywallet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.CreateCategory
import org.aristonis.mywallet.domain.usecase.DeleteDeductionItem
import org.aristonis.mywallet.domain.usecase.ObserveDeductionItems
import org.aristonis.mywallet.domain.usecase.SaveDeductionDefinition
import org.aristonis.mywallet.domain.usecase.SaveDeductionItem
import org.aristonis.mywallet.domain.usecase.SetDeductionItemEnabled

/** Supplies reusable deduction operations from the shared repository. */
@Module
@InstallIn(SingletonComponent::class)
object DeductionUseCaseModule {
    @Provides fun observe(items: DeductionItemRepository) = ObserveDeductionItems(items)
    @Provides fun save(items: DeductionItemRepository) = SaveDeductionItem(items)
    @Provides fun delete(items: DeductionItemRepository) = DeleteDeductionItem(items)
    @Provides fun enabled(items: DeductionItemRepository) = SetDeductionItemEnabled(items)
    @Provides fun definition(
        items: DeductionItemRepository,
        categories: CategoryRepository,
        createCategory: CreateCategory,
        saveItem: SaveDeductionItem,
        runner: TransactionRunner,
    ) = SaveDeductionDefinition(items, categories, createCategory, saveItem, runner)
}
